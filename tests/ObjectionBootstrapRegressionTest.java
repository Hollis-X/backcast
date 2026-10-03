package com.mkei.backcast.tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Runs the actual bundled Python launcher with controlled Frida outcomes. */
public final class ObjectionBootstrapRegressionTest {
    private static File root, modules;

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static String run(String mode, String version, boolean probe) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/python3", "-c", ObjectionBootstrap.program(), probe ? "version" : "run");
        builder.environment().put("HOME", new File(root, "home").getPath());
        builder.environment().put("PYTHONPATH", modules.getPath());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("BACKCAST_FRIDA_PID", "0");
        builder.environment().put("BACKCAST_FRIDA_PORT", "28765");
        builder.environment().put("BACKCAST_FRIDA_SERVER_VERSION", version);
        builder.environment().put("FIXTURE_MODE", mode);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.waitFor() == ("success".equals(mode) || probe ? 0 : 1), "Unexpected bootstrap outcome: " + output);
            return output;
        } finally { process.destroyForcibly(); }
    }

    private static List<JSONObject> events(String output) throws Exception {
        List<JSONObject> result = new ArrayList<JSONObject>();
        for (String line : output.split("\n")) if (line.startsWith(ObjectionBootstrap.MARKER)) {
            result.add(new JSONObject(line.substring(ObjectionBootstrap.MARKER.length())));
        }
        return result;
    }

    private static void versionDoesNotConnectOrModifyFridaMethods() throws Exception {
        String output = run("unready", "different", true);
        check(output.trim().equals("objection: 1.12.5") && events(output).isEmpty(), "Version probe touched a server/target");
    }

    private static void readinessExhaustionAndVersionMismatchStopBeforeAttach() throws Exception {
        for (String mode : new String[]{"unready", "mismatch"}) {
            String output = run(mode, "mismatch".equals(mode) ? "17.3.0" : "17.2.14", false);
            List<JSONObject> events = events(output);
            check(events.size() == 2 && "server_connect".equals(events.get(1).getString("phase"))
                    && "failed".equals(events.get(1).getString("state")) && !output.contains("CLI_STARTED"),
                    "Failed server readiness fell through into target CLI: " + output);
        }
    }

    private static void realPhaseWrappersDistinguishAttachCompilationLoadAndRpc() throws Exception {
        for (String phase : new String[]{"attach", "script_create", "script_load", "rpc"}) {
            String output = run(phase, "17.2.14", false);
            List<JSONObject> events = events(output);
            JSONObject last = events.get(events.size() - 1);
            check(phase.equals(last.getString("phase")) && "failed".equals(last.getString("state"))
                    && last.getLong("elapsed_ms") >= 0, "Wrong failing Frida stage: " + output);
            if ("attach".equals(phase)) check(!output.contains("\"phase\": \"script_create\""), "Attach timeout attempted script compilation");
            JSONObject attach = events.get(3);
            check("attach".equals(attach.getString("phase")) && attach.getBoolean("target_enumerated")
                    && attach.getLong("target_pid") > 0 && attach.getLong("target_start_ticks") >= 0,
                    "Target identity was missing or inferred from a stale PID: " + output);
        }
    }

    private static void successRetainsOriginalReturnValuesAndActualRuntimeIdentity() throws Exception {
        String output = run("success", "17.2.14", false);
        List<JSONObject> events = events(output);
        JSONObject first = events.get(0), last = events.get(events.size() - 1);
        check(first.getInt("port") == 28765 && first.getInt("runner_uid") >= 0
                && "17.2.14".equals(first.getString("client_version")) && "17.2.14".equals(first.getString("server_version"))
                && "rpc".equals(last.getString("phase")) && "completed".equals(last.getString("state"))
                && output.endsWith("ORIGINAL_RESULT\n"), "Instrumentation changed results or omitted runtime evidence: " + output);
    }

    private static void actualBundledClickConfirmsWhyLateConnectionOptionsMustBeRejected(File repo) throws Exception {
        String program = "import sys,tarfile,pathlib,json\n"
                + "archive=pathlib.Path(sys.argv[1]);modules=pathlib.Path(sys.argv[2])\n"
                + "with tarfile.open(archive) as tar:\n"
                + " for member in tar.getmembers():\n"
                + "  if member.isfile() and member.name.startswith('python-site/click/'):\n"
                + "   path=modules/member.name[len('python-site/'):];path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(tar.extractfile(member).read())\n"
                + "sys.path.insert(0,str(modules))\nimport click\n"
                + "@click.command()\n@click.option('--network','-N',is_flag=True)\n@click.option('--local','-L',is_flag=True)\n"
                + "@click.option('--host','-h',default='127.0.0.1')\n@click.option('--port','-P',default=27042)\n"
                + "@click.option('--name','-n')\ndef command(**kwargs):return kwargs\n"
                + "base=['--network','--host','127.0.0.1','--port','28765','-n','sample.running.app']\n"
                + "normal=command.main(args=base,standalone_mode=False)\n"
                + "changed=command.main(args=base+['--host=192.0.2.1','--port=1234'],standalone_mode=False)\n"
                + "assert normal['host']=='127.0.0.1' and normal['port']==28765\n"
                + "assert changed['host']=='192.0.2.1' and changed['port']==1234\n"
                + "print('CONFIRMED_LATE_OPTION_OVERRIDE')\n";
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/python3", "-c", program,
                new File(repo, "app/src/main/assets/toolchain/common.tar.gz").getAbsolutePath(), modules.getAbsolutePath());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1"); builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check(process.waitFor() == 0 && output.trim().equals("CONFIRMED_LATE_OPTION_OVERRIDE"), "Actual bundled Click behavior was not verified: " + output);
        } finally { process.destroyForcibly(); }
    }

    private static void remove(File file) throws Exception {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        Files.deleteIfExists(file.toPath());
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("backcast-objection-bootstrap-").toFile();
        try {
            modules = new File(root, "modules"); File console = new File(modules, "objection/console"); console.mkdirs();
            Files.write(new File(modules, "objection/__init__.py").toPath(), new byte[0]);
            Files.write(new File(console, "__init__.py").toPath(), new byte[0]);
            Files.write(new File(modules, "frida.py").toPath(), ("import os,time,types\n"
                    + "time.sleep=lambda duration:None\n__version__='17.2.14'\nmode=os.environ['FIXTURE_MODE']\n"
                    + "class TransportError(Exception):pass\nclass ServerNotRunningError(Exception):pass\nclass TimedOutError(Exception):pass\n"
                    + "class Script:\n def load(self):\n  if mode=='script_load':raise TimedOutError('load timed out')\n  return 'loaded'\n"
                    + " def _rpc_request(self):\n  if mode=='rpc':raise TimedOutError('RPC timed out')\n  return 'ORIGINAL_RESULT'\n"
                    + "class Session:\n def create_script(self,source):\n  if mode=='script_create':raise SyntaxError('unexpected character')\n  return Script()\n"
                    + "class Device:\n def enumerate_processes(self):\n  if mode=='unready':raise TransportError('not ready')\n  return [types.SimpleNamespace(pid=os.getpid())]\n"
                    + " def attach(self,pid):\n  if mode=='attach':raise TimedOutError('waiting for signal')\n  return Session()\n"
                    + "class Manager:\n def add_remote_device(self,address):return Device()\n"
                    + "def get_device_manager():return Manager()\ncore=types.SimpleNamespace(Device=Device,Session=Session,Script=Script)\n").getBytes(StandardCharsets.UTF_8));
            Files.write(new File(console, "cli.py").toPath(), ("import os,frida\n"
                    + "def cli(args,prog_name):\n"
                    + " if args[-1]=='version':print('objection: 1.12.5');return\n"
                    + " print('CLI_STARTED',flush=True)\n"
                    + " session=frida.get_device_manager().add_remote_device('127.0.0.1').attach(os.getpid())\n"
                    + " script=session.create_script('source');assert script.load()=='loaded'\n print(script._rpc_request())\n").getBytes(StandardCharsets.UTF_8));
            for (String method : new String[]{"versionDoesNotConnectOrModifyFridaMethods", "readinessExhaustionAndVersionMismatchStopBeforeAttach",
                    "realPhaseWrappersDistinguishAttachCompilationLoadAndRpc", "successRetainsOriginalReturnValuesAndActualRuntimeIdentity"}) {
                ObjectionBootstrapRegressionTest.class.getDeclaredMethod(method).invoke(null); System.out.println("PASS " + method);
            }
            actualBundledClickConfirmsWhyLateConnectionOptionsMustBeRejected(args.length == 0 ? new File(".") : new File(args[0]));
            System.out.println("PASS actualBundledClickConfirmsWhyLateConnectionOptionsMustBeRejected");
            System.out.println("5 Objection bootstrap tests passed");
        } finally { remove(root); }
    }
}
