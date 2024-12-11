package robaho.net.httpserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

public class StringUtilsTest {
    @Test
    public void TestContainsIgnoreCase() throws IOException {
        assertTrue(Utils.containsIgnoreCase("Keep-alive","keep-alive"));
        assertTrue(Utils.containsIgnoreCase("Keep-alive, upgrade","Upgrade"));
        assertTrue(Utils.containsIgnoreCase("upgrade, keep-alive","Upgrade"));
        assertTrue(Utils.containsIgnoreCase("upgrade, keep-alive","upgrade"));
        assertFalse(Utils.containsIgnoreCase("Keep-alive, upgrde","Upgrade"));
    }

}