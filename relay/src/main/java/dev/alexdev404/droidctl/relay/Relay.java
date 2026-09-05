package dev.alexdev404.droidctl.relay;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Bridges a TCP port on the Target to one of scrcpy's abstract unix sockets.
 *
 * <p>This exists because of a gap between two things that otherwise fit
 * together. scrcpy's {@code DesktopConnection} listens on a socket in Linux's
 * <em>abstract</em> namespace ({@code LocalServerSocket("scrcpy_<scid>")}), and
 * OpenSSH's {@code -L} forwarding reaches TCP ports and <em>filesystem</em> unix
 * sockets but not abstract ones. adb has no such problem: {@code adb forward
 * ... localabstract:} speaks the abstract namespace natively, which is why the
 * adb transport needs nothing like this.
 *
 * <p>So over SSH the chain becomes: Host loopback port &rarr; {@code ssh -L}
 * &rarr; this relay's TCP port on the Target &rarr; the abstract socket. This
 * process does the last hop and nothing else.
 *
 * <p>It is deliberately plain Java with no dependencies. It is pushed across the
 * same link the user is about to mirror over, so its dex is a few kilobytes
 * rather than the megabyte-plus a Kotlin runtime would add; and it runs under
 * {@code app_process} on the Target, in the Android runtime, where the app's own
 * language and library choices do not apply. Its sibling {@code :server} is
 * plain Java for the same reasons.
 *
 * <p>Usage, run on the Target through {@code app_process}:
 *
 * <pre>
 * CLASSPATH=/data/local/tmp/droidctl-relay.jar app_process / \
 *     dev.alexdev404.droidctl.relay.Relay scrcpy_0a1b2c3d [port]
 * </pre>
 *
 * <p>The chosen port is printed as {@code RELAY_PORT &lt;n&gt;} on the first
 * line of stdout, before anything else, so the caller can ask for an ephemeral
 * one and be told which it got.
 */
public final class Relay {

    /** Printed first, so the caller can learn an ephemeral port. */
    private static final String PORT_MARKER = "RELAY_PORT ";

    /** Only ever reached over an SSH tunnel, so never bound off-device. */
    private static final int BACKLOG = 8;

    private Relay() {
        // not instantiable
    }

    public static void main(String... args) {
        if (args.length < 1) {
            System.err.println("usage: Relay <abstract-socket-name> [tcp-port]");
            System.exit(2);
            return;
        }
        String abstractName = args[0];
        int requestedPort = args.length > 1 ? Integer.parseInt(args[1]) : 0;

        try {
            run(abstractName, requestedPort);
        } catch (IOException e) {
            System.err.println("relay: " + e);
            System.exit(1);
        }
    }

    private static void run(String abstractName, int requestedPort) throws IOException {
        // Loopback only. The tunnel terminates on this device, so binding
        // anywhere else would expose the Target's screen to the whole network.
        ServerSocket server =
                new ServerSocket(requestedPort, BACKLOG, InetAddress.getByName("127.0.0.1"));

        System.out.println(PORT_MARKER + server.getLocalPort());
        System.out.flush();

        // scrcpy expects two connections in order, video then control, and each
        // gets its own connection to the abstract socket -- exactly what
        // `adb forward` does. Accepting in a loop rather than twice keeps this
        // free of any assumption about how many the client will open.
        while (!server.isClosed()) {
            Socket tcp = server.accept();
            tcp.setTcpNoDelay(true);
            bridge(tcp, abstractName);
        }
    }

    private static void bridge(final Socket tcp, String abstractName) {
        final LocalSocket local = new LocalSocket();
        try {
            local.connect(new LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT));
        } catch (IOException e) {
            // The server is not listening yet. Closing without sending anything
            // gives the client the end of stream it already treats as "not up
            // yet", so it retries -- the same signal adb produces.
            System.err.println("relay: " + abstractName + " not available: " + e);
            closeQuietly(tcp);
            closeQuietly(local);
            return;
        }

        System.err.println("relay: bridged a connection to " + abstractName);
        pump(tcp, local, "tcp->local");
        pump(local, tcp, "local->tcp");
    }

    /**
     * Copies one direction on its own thread.
     *
     * <p>Each direction is closed independently, by shutting down only the
     * writing half of the destination. A stream that finishes must be able to
     * signal end of stream to its peer without tearing down the other
     * direction, which is still carrying data: scrcpy closes its control socket
     * before its video socket during teardown, and killing both at the first
     * close would truncate the video stream mid-frame.
     */
    private static void pump(final Object from, final Object to, final String name) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buffer = new byte[32 * 1024];
                try {
                    InputStream in = inputOf(from);
                    OutputStream out = outputOf(to);
                    while (true) {
                        int read = in.read(buffer);
                        if (read < 0) {
                            break;
                        }
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                } catch (IOException e) {
                    // Normal at teardown; anything else the peer will see as a
                    // closed stream and report for itself.
                } finally {
                    shutdownOutputQuietly(to);
                }
            }
        }, "relay-" + name);
        thread.setDaemon(false);
        thread.start();
    }

    private static InputStream inputOf(Object socket) throws IOException {
        return socket instanceof Socket
                ? ((Socket) socket).getInputStream()
                : ((LocalSocket) socket).getInputStream();
    }

    private static OutputStream outputOf(Object socket) throws IOException {
        return socket instanceof Socket
                ? ((Socket) socket).getOutputStream()
                : ((LocalSocket) socket).getOutputStream();
    }

    private static void shutdownOutputQuietly(Object socket) {
        try {
            if (socket instanceof Socket) {
                ((Socket) socket).shutdownOutput();
            } else {
                ((LocalSocket) socket).shutdownOutput();
            }
        } catch (IOException e) {
            // Already gone.
        }
    }

    private static void closeQuietly(Object socket) {
        try {
            if (socket instanceof Socket) {
                ((Socket) socket).close();
            } else {
                ((LocalSocket) socket).close();
            }
        } catch (IOException e) {
            // Already gone.
        }
    }
}
