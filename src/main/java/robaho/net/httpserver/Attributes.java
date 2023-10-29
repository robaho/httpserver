package robaho.net.httpserver;

/**
 * specialized HttpExchange attributes that can be used to control some
 * internals on a per connection basis
 */
public class Attributes {

    /**
     * an Integer which sets the size of the kernel socket write buffer
     */
    public static final String SOCKET_WRITE_BUFFER = "__SOCKET_WRITE_BUFFER";
}
