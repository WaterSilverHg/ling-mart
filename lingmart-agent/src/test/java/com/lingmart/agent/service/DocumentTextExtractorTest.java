package com.lingmart.agent.service;

import com.lingmart.agent.config.AgentProperties;
import com.lingmart.common.exception.BizException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentTextExtractor} 单测：编码探测、截断、列对齐、类型白名单。
 *
 * <p>全部是纯逻辑（不启 Spring 上下文），因为这些恰恰是最容易悄悄错的地方——
 * GBK 被当成 UTF-8 读不会报错、跳过空单元格会让列静默错位，两者在人工点一遍时都很难发现。</p>
 */
class DocumentTextExtractorTest {

    private AgentProperties properties;
    private DocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getDocument().setMaxChars(8000);
        properties.getDocument().setMaxRows(500);
        extractor = new DocumentTextExtractor(properties);
    }

    @Test
    void 纯文本_UTF8带BOM_应去掉BOM并保留中文() {
        byte[] bytes = withUtf8Bom("商品,价格\n苹果,9.9");

        DocumentTextExtractor.Extracted result = extractor.extract("清单.csv", bytes);

        // BOM 若没被去掉，第一个字段名会带上一个不可见字符，模型读到的列名就不是「商品」了
        assertTrue(result.text().startsWith("商品,价格"), "实际内容：" + result.text());
        assertEquals("2 行", result.summary());
        assertFalse(result.truncated());
    }

    @Test
    void 纯文本_GBK编码_应正确解码而不是变成乱码() {
        // 中文 Windows 下 Excel「另存为 CSV」的默认编码。
        // 注意：用 UTF-8 宽容模式解它**不会抛异常**，只是中文全变成替换字符——
        // 这正是这个用例存在的意义：它必须断言"内容正确"，不能只断言"没报错"。
        String original = "商品,价格\n苹果,9.9";
        byte[] bytes = original.getBytes(Charset.forName("GBK"));

        DocumentTextExtractor.Extracted result = extractor.extract("清单.csv", bytes);

        assertEquals("商品,价格\n苹果,9.9", result.text());
    }

    @Test
    void 纯文本_CRLF换行_应统一成LF且不产生空行() {
        byte[] bytes = "第一行\r\n第二行\r\n".getBytes(StandardCharsets.UTF_8);

        DocumentTextExtractor.Extracted result = extractor.extract("说明.txt", bytes);

        assertEquals("第一行\n第二行", result.text());
        assertEquals("2 行", result.summary());
    }

    @Test
    void 超过字符上限_应截断并标记() {
        properties.getDocument().setMaxChars(50);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("商品").append(i).append('\n');
        }

        DocumentTextExtractor.Extracted result = extractor.extract("清单.csv", sb.toString().getBytes(StandardCharsets.UTF_8));

        assertTrue(result.truncated(), "超过上限必须标记为已截断，前端要把它显示给用户");
        assertTrue(result.text().length() <= 50, "截断后长度不应超过上限，实际：" + result.text().length());
    }

    /**
     * 在正文前拼上 UTF-8 BOM（EF BB BF），模拟 Excel 导出的 CSV。
     *
     * <p>按字节拼而不是在字符串字面量里写 BOM：那样源码里会出现一个<b>看不见的字符</b>，
     * 谁改这里都看不出自己删掉的是什么。</p>
     */
    private static byte[] withUtf8Bom(String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[body.length + 3];
        result[0] = (byte) 0xEF;
        result[1] = (byte) 0xBB;
        result[2] = (byte) 0xBF;
        System.arraycopy(body, 0, result, 3, body.length);
        return result;
    }

    @Test
    void 文件名为空_应按不支持类型拒绝() {
        assertThrows(BizException.class, () -> extractor.extract(null, new byte[]{1, 2, 3}));
    }

    @Test
    void 不支持的扩展名_应拒绝而不是尝试解析() {
        assertThrows(BizException.class,
                () -> extractor.extract("木马.exe", "MZ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void xlsx_应读出显示值且空单元格不导致列错位() throws Exception {
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("标题");
            header.createCell(1).setCellValue("规格");
            header.createCell(2).setCellValue("价格");

            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("苹果");
            // 刻意**不创建**第 1 列：跳过空单元格会让"价格"左移到第 1 列，模型读到的价格其实是规格
            row.createCell(2).setCellValue(9.9);
            workbook.write(out);
            bytes = out.toByteArray();
        }

        DocumentTextExtractor.Extracted result = extractor.extract("清单.xlsx", bytes);

        assertEquals("标题\t规格\t价格\n苹果\t\t9.9", result.text());
        assertEquals("2 行 × 3 列", result.summary());
    }

    @Test
    void xlsx_超过行数上限_应停止解析并标记截断() throws Exception {
        properties.getDocument().setMaxRows(3);
        byte[] bytes;
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("商品");
            for (int i = 0; i < 10; i++) {
                sheet.createRow(i).createCell(0).setCellValue("商品" + i);
            }
            workbook.write(out);
            bytes = out.toByteArray();
        }

        DocumentTextExtractor.Extracted result = extractor.extract("清单.xlsx", bytes);

        assertEquals(3, result.text().split("\n").length, "只应读出上限内的行");
        assertTrue(result.truncated());
    }

    @Test
    void docx_应按文档流读出段落与表格() throws Exception {
        byte[] bytes;
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("我的小店");
            XWPFTable table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("标题");
            table.getRow(0).getCell(1).setText("价格");
            table.getRow(1).getCell(0).setText("苹果");
            table.getRow(1).getCell(1).setText("9.9");
            doc.write(out);
            bytes = out.toByteArray();
        }

        DocumentTextExtractor.Extracted result = extractor.extract("材料.docx", bytes);

        assertEquals("我的小店\n标题\t价格\n苹果\t9.9", result.text());
        assertEquals("1 段文字，2 行表格", result.summary());
    }

    @Test
    void 损坏的xlsx_应给出友好错误而不是抛出原始异常() {
        byte[] garbage = "这根本不是 xlsx".getBytes(StandardCharsets.UTF_8);

        BizException e = assertThrows(BizException.class, () -> extractor.extract("坏文件.xlsx", garbage));

        assertTrue(e.getMessage().contains("Excel"), "错误信息应指向 Excel 本身，实际：" + e.getMessage());
    }
}
