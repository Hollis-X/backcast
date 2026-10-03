package com.mkei.backcast.tool;

/** Private, per-invocation phase evidence from the real Frida calls. */
final class ObjectionBootstrap {
    static final String MARKER = "[backcast-frida] ";

    private ObjectionBootstrap() { }

    static String program() {
        return "import os,sys,time,json,frida\nfrom datetime import datetime\n"
                + "cache=os.path.join(os.path.expanduser('~'),'.objection')\nos.makedirs(cache,exist_ok=True)\n"
                + "with open(os.path.join(cache,'version_info'),'w') as cached:\n"
                + " json.dump({'remote_version':'1.12.5','last_check':datetime.now().strftime('%d%m%y %H:%M:%S')},cached)\n"
                + "from objection.console.cli import cli\n"
                + "port=os.environ.get('BACKCAST_FRIDA_PORT','27043')\n"
                + "if not any(x in sys.argv[1:] for x in ('--help','--version','version')):\n"
                + " def event(phase,state,**details):\n"
                + "  details.update(phase=phase,state=state)\n"
                + "  print('" + MARKER + "'+json.dumps(details,ensure_ascii=True),flush=True)\n"
                + " def identity(pid,prefix):\n"
                + "  details={}\n"
                + "  try:\n"
                + "   details[prefix+'_uid']=os.stat('/proc/%d'%pid).st_uid\n"
                + "   with open('/proc/%d/stat'%pid) as stat: fields=stat.read().rsplit(')',1)[1].split()\n"
                + "   details[prefix+'_start_ticks']=int(fields[19])\n"
                + "  except (OSError,ValueError,IndexError): pass\n"
                + "  return details\n"
                + " server_pid=int(os.environ['BACKCAST_FRIDA_PID'])\n"
                + " event('server_connect','started',client_version=frida.__version__,server_version=os.environ.get('BACKCAST_FRIDA_SERVER_VERSION',''),server_pid=server_pid,port=int(port),runner_uid=os.geteuid(),**identity(server_pid,'server'))\n"
                + " processes=None\n"
                + " server_version=os.environ.get('BACKCAST_FRIDA_SERVER_VERSION','').strip()\n"
                + " if server_version and server_version!=frida.__version__:\n"
                + "  event('server_connect','failed',error_type='VersionMismatch')\n  raise RuntimeError('Private Frida client/server versions differ; target attach was not attempted')\n"
                + " for attempt in range(30):\n"
                + "  os.kill(int(os.environ['BACKCAST_FRIDA_PID']),0)\n"
                + "  try:\n   processes=frida.get_device_manager().add_remote_device('127.0.0.1:'+port).enumerate_processes(); break\n"
                + "  except (frida.TransportError,frida.ServerNotRunningError):\n   time.sleep(0.1)\n"
                + " if processes is None:\n"
                + "  event('server_connect','failed')\n  raise RuntimeError('Private Frida server did not become ready; target attach was not attempted')\n"
                + " event('server_connect','completed',process_count=len(processes))\n"
                + " def observe(owner,method,phase):\n"
                + "  original=getattr(owner,method)\n"
                + "  def observed(self,*args,**kwargs):\n"
                + "   details={}\n"
                + "   if phase=='attach':\n"
                + "    pid=args[0] if args else kwargs.get('target')\n"
                + "    details.update(target_pid=pid,target_enumerated=any(p.pid==pid for p in processes))\n"
                + "    if isinstance(pid,int): details.update(identity(pid,'target'))\n"
                + "   event(phase,'started',**details)\n   started=time.monotonic()\n"
                + "   try: result=original(self,*args,**kwargs)\n"
                + "   except Exception as failure:\n"
                + "    event(phase,'failed',elapsed_ms=int((time.monotonic()-started)*1000),error_type=type(failure).__name__)\n    raise\n"
                + "   event(phase,'completed',elapsed_ms=int((time.monotonic()-started)*1000))\n   return result\n"
                + "  setattr(owner,method,observed)\n"
                + " observe(frida.core.Device,'attach','attach')\n"
                + " observe(frida.core.Session,'create_script','script_create')\n"
                + " observe(frida.core.Script,'load','script_load')\n"
                + " observe(frida.core.Script,'_rpc_request','rpc')\n"
                + " event('command','started')\n"
                + "cli(args=['--network','--host','127.0.0.1','--port',port]+sys.argv[1:],prog_name='objection')";
    }
}
