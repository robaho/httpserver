package robaho.net.httpserver.extras;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import robaho.net.httpserver.extras.MultipartFormParser.Part;
import static robaho.net.httpserver.extras.MultipartFormParser.parse;

public class MultipartFormParserTest {

    @Test
    public void testFiles() throws UnsupportedEncodingException, IOException {
        var body = "trash1\r\n";
        body += "------WebKitFormBoundaryvef1fLxmoUdYZWXp\r\n";
        body += "Content-Disposition: form-data; name=\"uploads[]\"; filename=\"A.txt\"\r\n";
        body += "Content-Type: text/plain\r\n";
        body += "\r\n\r\n";
        body += "@11X";
        body += "111Y\r\n";
        body += "111Z\rCCCC\nCCCC\r\nCCCCC@\r\n\r\n";
        body += "------WebKitFormBoundaryvef1fLxmoUdYZWXp\r\n";
        body += "Content-Disposition: form-data; name=\"uploads[]\"; filename=\"B.txt\"\r\n";
        body += "Content-Type: text/plain\r\n";
        body += "\r\n\r\n";
        body += "@22X";
        body += "222Y\r\n";
        body += "222Z\r222W\n2220\r\n666@\r\n";
        body += "------WebKitFormBoundaryvef1fLxmoUdYZWXp--\r\n";

        Path storage = Path.of("/tmp", "parser_test");
        storage.toFile().mkdirs();

        var results = parse("UTF-8", "Content-Type: multipart/form-data; boundary=----WebKitFormBoundaryvef1fLxmoUdYZWXp", new ByteArrayInputStream(body.getBytes("UTF-8")), storage);

        Assert.assertEquals(results.size(), 1);
        List<Part> values = results.get("uploads[]");
        Assert.assertEquals(values.size(), 2);

        var s = "\r\n";
        s += "@11X";
        s += "111Y\r\n";
        s += "111Z\rCCCC\nCCCC\r\nCCCCC@\r\n";

        Assert.assertEquals(s.getBytes("UTF-8"), Files.readAllBytes((values.get(0).file()).toPath()), "file1 failed");

        s = "\r\n";
        s += "@22X";
        s += "222Y\r\n";
        s += "222Z\r222W\n2220\r\n666@";

        Assert.assertEquals(s.getBytes("UTF-8"), Files.readAllBytes((values.get(1).file()).toPath()), "file2 failed");
    }

    @Test
    public void testBinary() throws IOException {
        String hex
                = // data generated using curl --form
                "0d 0a "
                + "2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d " // ----------------
                + "2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 35 38 32 34 33 32 " // ----------582432
                + "38 64 62 37 65 32 33 30 61 35 0d 0a 43 6f 6e 74 " // 8db7e230a5..Cont
                + "65 6e 74 2d 44 69 73 70 6f 73 69 74 69 6f 6e 3a " // ent-Disposition:
                + "20 66 6f 72 6d 2d 64 61 74 61 3b 20 6e 61 6d 65 " //  form-data; name
                + "3d 22 70 69 63 74 75 72 65 5b 75 70 6c 6f 61 64 " // ="picture[upload
                + "65 64 5f 64 61 74 61 5d 22 3b 20 66 69 6c 65 6e " // ed_data]"; filen
                + "61 6d 65 3d 22 73 6d 61 6c 6c 2e 70 6e 67 22 0d " // ame="small.png".
                + "0a 43 6f 6e 74 65 6e 74 2d 54 79 70 65 3a 20 69 " // .Content-Type: i
                + "6d 61 67 65 2f 70 6e 67 0d 0a 0d 0a 89 50 4e 47 " // mage/png.....PNG
                + "0d 0a 1a 0a 00 00 00 0d 49 48 44 52 00 00 01 00 " // ........IHDR....
                + "00 00 01 00 01 03 00 00 00 66 bc 3a 25 00 00 00 " // .........f.:%...
                + "03 50 4c 54 45 b5 d0 d0 63 04 16 ea 00 00 00 1f " // .PLTE...c.......
                + "49 44 41 54 68 81 ed c1 01 0d 00 00 00 c2 a0 f7 " // IDATh...........
                + "4f 6d 0e 37 a0 00 00 00 00 00 00 00 00 be 0d 21 " // Om.7...........!
                + "00 00 01 9a 60 e1 d5 00 00 00 00 49 45 4e 44 ae " // ....`......IEND.
                + "42 60 82 0d 0a 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d " // B`...-----------
                + "2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 2d 35 " // ---------------5
                + "38 32 34 33 32 38 64 62 37 65 32 33 30 61 35 2d " // 824328db7e230a5-
                + "2d 0d 0a";                                       // -..

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        Arrays.stream(hex.split(" ")).map(s -> Integer.parseInt(s, 16)).forEach(i -> bos.write(i));
        byte[] input = bos.toByteArray();

        String boundary = "------------------------5824328db7e230a5";

        Path storage = Path.of("/tmp", "parser_test");
        storage.toFile().mkdirs();

        var results = parse("UTF-8", "Content-Type: multipart/form-data; boundary=" + boundary, new ByteArrayInputStream(input), storage);

        Assert.assertEquals(results.size(), 1);
        List<Part> values = results.get("picture[uploaded_data]");
        Assert.assertEquals(values.size(), 1);

        Assert.assertEquals(Files.readAllBytes(Path.of("src/test/resources/small.png")), Files.readAllBytes((values.get(0).file()).toPath()), "parse failed");
    }
}
