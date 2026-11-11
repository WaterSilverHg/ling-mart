package com.lingmart.agent.service;

import com.lingmart.agent.config.AgentProperties;
import com.lingmart.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 用户上传文档 → 受限纯文本（开店材料解析）。
 *
 * <p>输出的是<b>制表符分隔的文本</b>而不是结构化 JSON：模型读表格文本最稳，
 * 且服务端不必为每种来源定义一套结构化契约——解析出"要卖什么"本来就是模型该干的事，
 * 服务端只负责把文件安全、可控地变成它能读的东西。</p>
 *
 * <p><b>超限只截断、不报错</b>：用户要的是一份能用的草稿，不是"你的表太大"。截断量会写在
 * {@link Extracted#truncated()} 里，一路带到前端附件条上，让用户知道少了一部分。</p>
 *
 * <p>不支持的类型一律 {@link BizException} 拒绝（白名单，不是黑名单）。</p>
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    private static final String EXT_CSV = ".csv";
    private static final String EXT_TXT = ".txt";
    private static final String EXT_MD = ".md";
    private static final String EXT_XLSX = ".xlsx";
    private static final String EXT_DOCX = ".docx";

    private static final String GBK = "GBK";

    /**
     * BOM 的码点（U+FEFF）。
     *
     * <p>用码点常量而不是字符字面量：Unicode 转义在源码里会还原成一个<b>看不见的字符</b>，
     * 直接写字面量也一样——两者都会让"这个常量到底是什么"变得不可读、也容易被编辑器悄悄改掉。
     * 写成 0xFEFF 则完全是可见的 ASCII。</p>
     */
    private static final int BOM_CODE = 0xFEFF;

    private final AgentProperties properties;

    public DocumentTextExtractor(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 抽取正文。
     *
     * @param filename 原始文件名（仅取扩展名决定解析方式；文件名本身不参与解析）
     * @param bytes    文件字节
     * @return 正文 + 摘要 + 是否被截断
     * @throws BizException 类型不支持、文件损坏或加密
     */
    public Extracted extract(String filename, byte[] bytes) {
        AgentProperties.Document cfg = properties.getDocument();
        return switch (extensionOf(filename)) {
            case EXT_CSV, EXT_TXT, EXT_MD -> fromPlainText(decodeBytes(bytes), cfg);
            case EXT_XLSX -> fromXlsx(bytes, cfg);
            case EXT_DOCX -> fromDocx(bytes, cfg);
            default -> throw new BizException("不支持的文件类型，请上传 csv / txt / md / xlsx / docx");
        };
    }

    // ---------------- 纯文本（csv / txt / md） ----------------

    /**
     * 纯文本直接按行收下，<b>不做 CSV 分列</b>：逗号分隔的内容模型读得懂，
     * 而分列就要处理引号转义、字段内换行等一整串边界，收益却只是省掉一次模型的"心算"。
     */
    private static Extracted fromPlainText(String text, AgentProperties.Document cfg) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        long total = normalized.lines().filter(line -> !line.isBlank()).count();
        CappedText out = new CappedText(cfg.getMaxChars());
        for (String line : normalized.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            if (!out.appendLine(line)) {
                break;
            }
        }
        return new Extracted(out.text(), total + " 行", out.truncated());
    }

    /**
     * 解码字节。
     *
     * <p>⚠️ 中文 Windows 下 Excel「另存为 CSV」默认写的是 <b>GBK</b>。用 UTF-8 宽容模式解
     * <b>不会报错</b>，只是中文全变成替换字符——这正是最容易"看起来读到了、其实全是乱码"的坑。
     * 故先用 REPORT 模式试解（遇到非法字节序列即抛），失败再退回 GBK。
     * 纯 ASCII 的文件两种编码一致，走哪条分支都对。</p>
     */
    private static String decodeBytes(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return stripBom(text);
        } catch (CharacterCodingException e) {
            if (!Charset.isSupported(GBK)) {
                throw new BizException("文件编码无法识别（既不是 UTF-8 也不是 GBK），请另存为 UTF-8 后重试");
            }
            return stripBom(new String(bytes, Charset.forName(GBK)));
        }
    }

    /** 去掉 BOM：Excel 导出的 CSV 会带，留着会在第一个字段里混进一个不可见字符。 */
    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == BOM_CODE ? text.substring(1) : text;
    }

    // ---------------- xlsx ----------------

    /**
     * 读首个工作表，按行拼成制表符分隔的文本。
     *
     * <p>用 {@link DataFormatter} 而不是 {@code cell.getStringCellValue()}：前者给出的是
     * <b>Excel 里显示的值</b>（数字按格式、日期按格式、布尔按显示），后者对数字/日期单元格会直接抛错。
     * 用 {@code formatCellValue(cell)} 单参版本、不带公式求值器：<b>不计算公式</b>——
     * 求值任意用户上传的公式等于执行不可信代码；这类单元格会显示成公式原文，可以接受。</p>
     */
    private static Extracted fromXlsx(byte[] bytes, AgentProperties.Document cfg) {
        CappedText out = new CappedText(cfg.getMaxChars());
        DataFormatter formatter = new DataFormatter();
        int rows = 0;
        int maxCols = 0;
        boolean rowLimitHit = false;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                return new Extracted("", "空工作簿", false);
            }
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (rows >= cfg.getMaxRows()) {
                    rowLimitHit = true;
                    break;
                }
                String line = rowToLine(row, formatter);
                if (line.isEmpty()) {
                    continue;
                }
                rows++;
                maxCols = Math.max(maxCols, row.getLastCellNum() - row.getFirstCellNum());
                if (!out.appendLine(line)) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("解析 xlsx 失败：{}", e.getMessage());
            throw new BizException("这个 Excel 读不出来，请确认它是 .xlsx 且未加密、未损坏");
        }
        return new Extracted(out.text(), rows + " 行 × " + maxCols + " 列",
                out.truncated() || rowLimitHit);
    }

    /**
     * 一行 → 制表符分隔的文本。
     *
     * <p>按 {@code firstCellNum..lastCellNum} 逐列取、<b>空单元格补空串</b>：
     * 跳过空格会把后面的列整体左移，模型读到的"价格"其实是"库存"，而它自己发现不了。</p>
     */
    private static String rowToLine(Row row, DataFormatter formatter) {
        short first = row.getFirstCellNum();
        short last = row.getLastCellNum();
        if (first < 0 || last < 0) {
            return "";
        }
        StringBuilder line = new StringBuilder();
        for (int c = first; c < last; c++) {
            if (c > first) {
                line.append('\t');
            }
            Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell != null) {
                line.append(flatten(formatter.formatCellValue(cell)));
            }
        }
        String text = line.toString();
        // 整行都是空串时按空行丢弃，避免一堆空制表符挤占字符预算
        return text.isBlank() ? "" : text;
    }

    // ---------------- docx ----------------

    /** 按文档流顺序读段落与表格：段落一行，表格每行一条制表符分隔的文本。 */
    private static Extracted fromDocx(byte[] bytes, AgentProperties.Document cfg) {
        CappedText out = new CappedText(cfg.getMaxChars());
        int paragraphs = 0;
        int tableRows = 0;
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText();
                    if (text != null && !text.isBlank()) {
                        paragraphs++;
                        if (!out.appendLine(text.trim())) {
                            break;
                        }
                    }
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        StringBuilder line = new StringBuilder();
                        for (XWPFTableCell cell : row.getTableCells()) {
                            if (!line.isEmpty()) {
                                line.append('\t');
                            }
                            line.append(cell.getText() == null ? "" : flatten(cell.getText()));
                        }
                        if (line.isEmpty()) {
                            continue;
                        }
                        tableRows++;
                        if (!out.appendLine(line.toString())) {
                            break;
                        }
                    }
                }
                if (out.truncated()) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("解析 docx 失败：{}", e.getMessage());
            throw new BizException("这个 Word 文档读不出来，请确认它是 .docx（不支持老式 .doc）且未加密");
        }
        String summary = paragraphs + " 段文字" + (tableRows > 0 ? "，" + tableRows + " 行表格" : "");
        return new Extracted(out.text(), summary, out.truncated());
    }

    // ---------------- 工具 ----------------

    /** 压掉单元格内的换行与制表符：它们会破坏"一行一条记录"的结构，让模型读串行。 */
    private static String flatten(String value) {
        return value.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    /** 扩展名（小写含点）；无扩展名返回空串。 */
    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    /**
     * 解析结果。
     *
     * @param text      正文（已按上限截断）
     * @param summary   摘要，如「12 行 × 4 列」「36 行」「3 段文字，8 行表格」
     * @param truncated 是否因超限被截断
     */
    public record Extracted(String text, String summary, boolean truncated) {
    }

    /**
     * 带长度上限的累加器：达到上限后 {@link #appendLine} 返回 {@code false}，
     * 调用方据此<b>提前停止解析</b>——而不是先把整张表读进内存再截断。
     */
    private static final class CappedText {

        private final StringBuilder sb = new StringBuilder();
        private final int max;
        private boolean truncated;

        CappedText(int max) {
            this.max = Math.max(1, max);
        }

        boolean appendLine(String line) {
            if (sb.length() >= max) {
                truncated = true;
                return false;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(line);
            if (sb.length() > max) {
                sb.setLength(max);
                truncated = true;
                return false;
            }
            return true;
        }

        String text() {
            return sb.toString();
        }

        boolean truncated() {
            return truncated;
        }
    }
}
