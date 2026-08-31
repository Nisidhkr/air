package p2p;

import p2p.controller.FileController;
import java.io.IOException;

/**
 * Air - P2P File Sharing Application
 */
public class App {
    public static void main(String[] args) {
        // Port precedence: first CLI arg > PORT env var > 9090.
        int port = 7000;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        } else if (System.getenv("PORT") != null) {
            port = Integer.parseInt(System.getenv("PORT"));
        }
        try {
            FileController fileController = new FileController(port);
            fileController.start();

            System.out.println("Air server started on port " + port);
            System.out.println("UI available at http://localhost:3000");
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                fileController.stop();
            }));
            
            System.out.println("Press Enter to stop the server");
            int key = System.in.read();
            if (key == -1) {
                // No interactive stdin (service/container): stay alive until killed.
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
        } catch (IOException e) {
            System.err.println("Error starting server: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
