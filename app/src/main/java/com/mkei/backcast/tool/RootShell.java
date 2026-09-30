package com.mkei.backcast.tool;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 应用进程读不到的路径，跟 shell 一样走 root。
 * 探测结果在这次进程里只问一次。
 */
final class RootShell {

    static final class Out {
        final int exit;
        final byte[] stdout;
        final String stderr;

        Out(int exit, byte[] stdout, String stderr) {
            this.exit = exit;
            this.stdout = stdout == null ? new byte[0] : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }
    }

    private static volatile Boolean available;

    private RootShell() {
    }

    static boolean available() {
        Boolean cached = available;
        if (cached != null) {
            return cached.booleanValue();
        }
        synchronized (RootShell.class) {
            if (available != null) {
                return available.booleanValue();
            }
            boolean ok = false;
            try {
                Out out = exec("id", null, 400, 10000);
                String text = new String(out.stdout, "UTF-8");
                ok = out.exit == 0 && text.indexOf("uid=0") >= 0;
            } catch (Exception e) {
                ok = false;
            }
            available = Boolean.valueOf(ok);
            return ok;
        }
    }

    static String quote(String path) {
        if (path == null) {
            return "''";
        }
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("路径不合法");
        }
        return "'" + path.replace("'", "'\\''") + "'";
    }

    static Process start(String command) throws Exception {
        return new ProcessBuilder("su", "-c", command).start();
    }

    /** 读完整份标准输出。超过 max 就停掉，不把大文件整段留在内存里。 */
    static byte[] readAll(String command, int max) throws Exception {
        Process process = start(command);
        process.getOutputStream().close();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread terr = drain(process.getErrorStream(), err, 4000, null);
        InputStream in = process.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        boolean over = false;
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) {
                    continue;
                }
                if (total > max - n) {
                    over = true;
                    process.destroy();
                    break;
                }
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            int exit = waitFor(process, 60000);
            terr.join(300);
            if (over) {
                throw new IllegalArgumentException("文件超过 " + max + " 字节，不能整篇载入。");
            }
            if (exit != 0) {
                String msg = new String(err.toByteArray(), "UTF-8").trim();
                throw new IllegalArgumentException(msg.length() == 0 ? "读取失败" : msg);
            }
        }
        return out.toByteArray();
    }

    static Out exec(String command, byte[] stdin, int maxStdout, int timeoutMs) throws Exception {
        Process process = start(command);
        if (stdin != null && stdin.length > 0) {
            OutputStream os = process.getOutputStream();
            os.write(stdin);
            os.close();
        } else {
            process.getOutputStream().close();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread tout = drain(process.getInputStream(), out, maxStdout, process);
        Thread terr = drain(process.getErrorStream(), err, 4000, null);
        int exit = waitFor(process, timeoutMs);
        tout.join(300);
        terr.join(300);
        return new Out(exit, out.toByteArray(), new String(err.toByteArray(), "UTF-8"));
    }

    static int waitFor(Process process, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException still) {
                if (System.currentTimeMillis() >= deadline) {
                    process.destroy();
                    return -1;
                }
                Thread.sleep(30);
            }
        }
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream out,
                                final int max, final Process kill) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n == 0) {
                            continue;
                        }
                        synchronized (out) {
                            if (out.size() < max) {
                                int room = max - out.size();
                                out.write(buf, 0, Math.min(n, room));
                            } else if (kill != null) {
                                kill.destroy();
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
