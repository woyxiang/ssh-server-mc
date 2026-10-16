package io.github.woyxiang.sshserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoundedLogReaderTest {

    @TempDir
    Path dir;

    @Test
    void collectsOnlyBytesAfterOffset() throws Exception {
        Path log = dir.resolve("latest.log");
        Files.writeString(log, "old line\n", StandardCharsets.UTF_8);
        long offset = Files.size(log);
        Files.writeString(log, "new line\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        try (BoundedLogReader r = new BoundedLogReader(log, offset, 1024)) {
            assertTrue(r.readAvailable() > 0);
            assertEquals("new line\n", r.text(), "只收集派发时刻之后的新增内容");
            assertFalse(r.truncated());
            assertEquals(0, r.readAvailable(), "无新增时返回 0");
        }
    }

    @Test
    void capsOutputAtMaxBytesAndFlagsTruncation() throws Exception {
        Path log = dir.resolve("latest.log");
        String big = "x".repeat(500);
        Files.writeString(log, big, StandardCharsets.UTF_8);

        try (BoundedLogReader r = new BoundedLogReader(log, 0, 100)) {
            assertEquals(100, r.readAvailable());
            assertTrue(r.truncated(), "超出上限应置截断标记");
            assertEquals(100, r.text().length(), "收集量受上限约束");
            assertEquals(0, r.readAvailable(), "触顶后不再读取");
        }
    }

    @Test
    void missingFileReadsAsEmptyInsteadOfFailing() throws Exception {
        try (BoundedLogReader r = new BoundedLogReader(dir.resolve("nope.log"), 0, 100)) {
            assertEquals(0, r.readAvailable());
            assertEquals("", r.text());
            assertFalse(r.truncated());
        }
    }

    @Test
    void truncatedFileStartsFromBeginning() throws Exception {
        Path log = dir.resolve("latest.log");
        Files.writeString(log, "abc", StandardCharsets.UTF_8);
        try (BoundedLogReader r = new BoundedLogReader(log, 999, 100)) {
            assertEquals(3, r.readAvailable(), "偏移量超过文件长度（已轮转）→ 从头收集");
            assertEquals("abc", r.text());
        }
    }
}
