#!/usr/bin/env python3
"""Run on a disposable Docker daemon: SALVAGE_TEST_DOCKER=isolated python3 tests/docker_safety.py IMAGE.

DOCKER_COMMAND optionally supplies a CLI prefix for an isolated remote daemon.
Only generated data is used. The synthetic crane makes failures deterministic;
real Restic/PostgreSQL restores are covered by the documented release validation.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

if os.environ.get('SALVAGE_TEST_DOCKER') != 'isolated':
    raise SystemExit('Requires explicit SALVAGE_TEST_DOCKER=isolated and a disposable Docker daemon')
D = shlex.split(os.environ.get('DOCKER_COMMAND', 'docker'))
IMAGE = sys.argv[1]
HERE = Path(__file__).resolve().parent
PREFIX = 'salvage-test-' + uuid.uuid4().hex[:10]
CRANE = PREFIX + ':crane'
containers, volumes = [], []

def d(*args, check=True, timeout=180, **kwargs):
    p = subprocess.run(D + list(args), capture_output=True, text=True, timeout=timeout, **kwargs)
    if check and p.returncode:
        raise AssertionError(f'{args[:3]}: {p.stdout}{p.stderr}')
    return p

def labels(values):
    return [x for k, v in values.items() for x in ('--label', k+'='+v)]

def volume(suffix):
    name=PREFIX+'-'+suffix
    d('volume','create',name);volumes.append(name)
    return name

def container(name, *args):
    name=PREFIX+'-'+name;containers.append(name)
    return d('run','-d','--name',name,*args).stdout.strip(),name

def running(name):
    return json.loads(d('inspect',name).stdout)[0]['State']['Running']

def source(name, **extra):
    vol=volume(name)
    config={'salvage.tide.test':'g:'+vol,'salvage.action':'stop'}|extra
    _,app=container(name,'-v',vol+':/data',*labels(config),'--entrypoint','sh',CRANE,
                    '-c','trap "exit 0" TERM; while :; do sleep 1; done')
    d('exec',app,'sh','-c','echo intact-source > /data/payload')
    return vol,app

def root(mode='copy'):
    config={'salvage.root':'true','salvage.tides.test.cron':'* * * * *',
            'salvage.tides.test.grouping':'project','salvage.tides.test.crane':'test',
            'salvage.tides.test.maxConcurrent':'1','salvage.cranes.test.image':CRANE,
            'salvage.cranes.test.env.MODE':mode,
            'salvage.cranes.test.mount.'+repository:'/repo'}
    return ['-v','/var/run/docker.sock:/var/run/docker.sock','-v',tools+':/tools:ro',
            '-e','MACHINE=integration',*labels(config)]

def tide(mode='copy'):
    name=PREFIX+'-root';containers.append(name)
    p=d('run','--name',name,*root(mode),'--entrypoint','java',IMAGE,
        '-cp','/app/resources:/app/classes:/app/libs/*','/tools/ReviewRunner.java')
    d('rm',name);containers.remove(name)
    return p.stdout+p.stderr

def remove(app):
    d('rm','-f',app);containers.remove(app)

if d('ps','-aq','--filter','label=salvage.root').stdout.strip():
    raise SystemExit('An existing Salvage root is present; refusing to run')

try:
    # Build from the candidate's base; no unrelated image or network required.
    dockerfile=f'''FROM {IMAGE}
RUN mkdir -p /salvage/volume /salvage/meta
ENTRYPOINT ["sh", "-c", "case $MODE in slow) sleep 90;; fail) exit 7;; *) if touch /salvage/volume/SHOULD_NOT_WRITE 2>/dev/null; then exit 99; fi; tar -cf /repo/$SALVAGE_VOLUME_NAME.tar -C /salvage volume meta;; esac"]
'''
    d('build','-t',CRANE,'-',input=dockerfile)
    tools=volume('tools');repository=volume('repo')
    _,seed=container('tools-seed','-v',tools+':/tools','--entrypoint','sleep',IMAGE,'infinity')
    for name in ('ReviewRunner.java','RollbackProbe.java'):
        d('cp',str(HERE/name),seed+':/tools/'+name)
    remove(seed)
    vol,app=source('global')
    log=tide()
    assert 'successfully finished back up volumes' in log,log
    assert running(app)
    restore=volume('restore')
    p=d('run','--rm','-v',repository+':/repo:ro','-v',restore+':/restore',
        '--entrypoint','sh',CRANE,'-c',f'tar -xf /repo/{vol}.tar -C /restore; cat /restore/volume/payload')
    assert p.stdout=='intact-source\n'
    # An immediately exiting crane must keep its exit status until collected.
    for _ in range(3):
        assert 'successfully finished back up volumes' in tide()
    assert not d('ps','-aq','--filter','label=salvage.entity=crane').stdout.strip()
    remove(app)
    print('PASS global volume, read-only source, exact restore and fast crane exit',flush=True)

    vol,app=source('pre',**{'salvage.command.pre':'sh -c "exit 7"','salvage.command.exitcode':'7'})
    log=tide()
    assert 'successfully finished' not in log and 'exited with code 7' in log,log
    assert running(app)
    assert d('run','--rm','-v',repository+':/repo','--entrypoint','test',CRANE,'-e','/repo/'+vol+'.tar',check=False).returncode!=0
    remove(app)
    vol,app=source('post',**{'salvage.command.pre':'true','salvage.command.post':'sh -c "exit 9"'})
    log=tide()
    assert 'successfully finished' not in log and 'exited with code 9' in log,log
    assert running(app);remove(app)
    vol,app=source('failure')
    assert 'failed partially' in tide('fail')
    assert running(app);remove(app)
    print('PASS preparation and post-command failures, failed crane recovery',flush=True)

    _,a=source('rollback-a',**{'com.docker.compose.project':PREFIX})
    _,b=source('rollback-b',**{'com.docker.compose.project':PREFIX})
    # No coordinator root is needed for this direct production-class probe.
    result=d('run','--rm','-v','/var/run/docker.sock:/var/run/docker.sock','-v',tools+':/tools:ro',
             '--entrypoint','java',IMAGE,'-cp','/app/resources:/app/classes:/app/libs/*',
             '/tools/RollbackProbe.java',a,b)
    assert 'ROLLBACK_OK' in result.stdout,result.stdout+result.stderr
    assert running(a) and running(b)
    # Two volumes, one worker: shutdown must handle active and queued work.
    _,daemon=container('root',*root('slow'),IMAGE)
    deadline=time.monotonic()+100
    while time.monotonic()<deadline:
        if not running(a) and d('ps','-q','--filter','label=salvage.entity=crane').stdout.strip():break
        time.sleep(1)
    else:raise AssertionError('Scheduled backup did not start')
    d('stop','--time','20',daemon,timeout=30)
    daemon_state=json.loads(d('inspect',daemon).stdout)[0]['State']
    log=d('logs',daemon).stdout
    # A JVM handling SIGTERM may retain the conventional 128 + SIGTERM status.
    assert daemon_state['ExitCode'] in (0,143),daemon_state
    assert 'exiting salvage service thread' in log,log
    assert running(a) and running(b),log
    assert not d('ps','-aq','--filter','label=salvage.entity=crane').stdout.strip(),log
    print('PASS multi-container rollback and graceful shutdown with an active crane',flush=True)
finally:
    # Names are all generated by this invocation; no global pruning.
    for name in reversed(containers):d('rm','-f',name,check=False)
    for name in reversed(volumes):d('volume','rm',name,check=False)
    d('image','rm',CRANE,check=False)
