package robaho.net.httpserver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test;

public class RequestHeadersTest {
    @Test
    public void TestHeaderParse() throws IOException {
        String request = "GET blah\r\nKEY:VALUE\r\n\r\n";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VALUE");
    }
    @Test
    public void TestMultiLineHeaderParse() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\n 2\r\n\r\n";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL2");
    }
    @Test
    public void TestMultipleHeaders() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\nKEY2:VAL2\r\n\r\n";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL");
        assertEquals(r.headers().getFirst("KEY2"),"VAL2");
    }
    @Test
    public void TestMultipleHeadersThenBody() throws IOException {
        String request = "GET blah\r\nKEY:VAL\r\nKEY2:VAL2\r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL");
        assertEquals(r.headers().getFirst("KEY2"),"VAL2");
    }
    @Test
    public void TestWhitespace() throws IOException {
        String request = "GET blah\r\nKEY : VAL\r\nKEY2:VAL2 \r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().getFirst("KEY"),"VAL");
        assertEquals(r.headers().getFirst("KEY2"),"VAL2");
    }

    @Test
    public void TestDuplicateHeaders() throws IOException {
        String request = "GET blah\r\nKEY : VAL\r\nKEY:VAL2\r\nKEY:VAL3 \r\n\r\nSome Body Data";
        var is = new ByteArrayInputStream(request.getBytes());
        var os = new ByteArrayOutputStream();

        Request r = new Request(is,os);
        assertTrue("GET blah".contentEquals(r.requestLine()));
        assertEquals(r.headers().get("KEY"), List.of("VAL", "VAL2", "VAL3"));
    }
}