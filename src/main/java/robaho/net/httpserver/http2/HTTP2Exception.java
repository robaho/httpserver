package robaho.net.httpserver.http2;

public class HTTP2Exception extends Exception {
	
	private final HTTP2ErrorCode errorCode;

	public HTTP2Exception(HTTP2ErrorCode errorCode) {
		this(errorCode,"");
	}

	public HTTP2Exception(HTTP2ErrorCode errorCod, String message) {
		super(message);
        errorCode = errorCod;
	}
    
	public HTTP2Exception(HTTP2ErrorCode errorCod, Throwable cause) {
		super(cause);
        errorCode = errorCod;
	}
	
	public HTTP2Exception(String message,Exception cause) {
        super(message,cause);
		errorCode = HTTP2ErrorCode.INTERNAL_ERROR;
	}
	public HTTP2ErrorCode getErrorCode()
	{
		return errorCode;
	}
	
}
