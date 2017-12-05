import sys, os
import os.path as osp
import time
import json
import re
import pickle
from subprocess import Popen, PIPE

def main():
  n = sys.argv[1]
  f = sys.argv[2] 
  print sys.argv
  name = osp.splitext(osp.basename(f))[0]
  process = []
  activationIds = []
  durations = {}
  os.system(" ".join(["bin/wsk -i action create", name, f]))
  cmd = " ".join(["bin/wsk -i action invoke", name] + sys.argv[3:]) 
  print(cmd)
  for i in xrange(10):
    process.append(Popen(cmd, shell=True, stdout=PIPE))
  for p in process:
    out, _ = p.communicate()
    m = re.match('ok: invoked .* with id (\w+)', out)
    activationIds.append(m.group(1)) 

  def fetch(field):
    def f(id):
      g = Popen(" ".join(["bin/wsk -i activation get", id]), shell=True, stdout=PIPE)
      ret, _ = g.communicate()
      m = re.match('ok: got activation \w+\n(\{.*\})\n', ret, re.DOTALL)
      if not m:
        return True
      result = json.loads(m.group(1))
      durations[id] = result[field] 
      return False
    return f

  while activationIds:
    print 'waiting for {} activations to finish.'.format(len(activationIds))
    time.sleep(5)  
    activationIds = filter(fetch('duration'), activationIds) 
  os.system(" ".join(["bin/wsk -i action delete", name]))
  with open(osp.splitext(f)[0] + '.pkl', 'w') as fid:
    pickle.dump(osp.splitext(f)[0] + '.pkl', fid)
  print durations

if __name__ == '__main__':
  main()
