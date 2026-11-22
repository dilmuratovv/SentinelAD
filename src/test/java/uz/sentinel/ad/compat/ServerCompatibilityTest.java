package uz.sentinel.ad.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCompatibilityTest {
    @Test
    void acceptsSupportedVersions() {
        for (String v : new String[]{"1.21", "1.21.4", "1.21.11", "1.21-rc1", "26.1.1", "26.3", "27.0", "26.3.build.205-stable", " 1.21.4 "}) {
            assertTrue(ServerCompatibility.isSupported(v), v);
        }
    }

    @Test
    void rejectsOldOrInvalidVersions() {
        for (String v : new String[]{"1.20.6", "1.8.8", "1.9", "", "abc", "25.1"}) {
            assertFalse(ServerCompatibility.isSupported(v), v);
        }
        assertFalse(ServerCompatibility.isSupported(null));
    }
}
