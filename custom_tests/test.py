import sys, os
import os.path as osp
import time
import json
import re
import pickle
import random
from subprocess import Popen, PIPE

n = 200
p = [0.5, 0.5]
cfgs = [
         ['custom_rules/cpu_intensive.py', '--param t 50'],
         ['custom_rules/curl_test.py', '']
       ]

class Action:
  def __init__(self, cfg):
    self.file_name = cfg[0]
    print self.file_name
    self.name = osp.splitext(osp.basename(self.file_name))[0]
    self.args = cfg[1]
    self.ids = []
    self.procs = []
    self.results = {}

  def create(self):
    os.system(" ".join(["bin/wsk -i action create", self.name, self.file_name]))

  def invoke(self):
    cmd = " ".join(["bin/wsk -i action invoke", self.name, self.args])
    p = Popen(cmd, shell=True, stdout=PIPE)
    out, _ = p.communicate()
    m = re.match('ok: invoked .* with id (\w+)', out)
    self.ids.append(m.group(1)) 

  def delete(self):
    os.system(" ".join(["bin/wsk -i action delete", self.name]))

  def getAllResult(self, field='duration'):
    def f(id):
      g = Popen(" ".join(["bin/wsk -i activation get", id]), shell=True, stdout=PIPE)
      ret, _ = g.communicate()
      m = re.match('ok: got activation \w+\n(\{.*\})\n', ret, re.DOTALL)
      if not m:
        return True
      result = json.loads(m.group(1))
      self.results[id] = result[field]
      return False

    self.ids = filter(f, self.ids)
    return len(self.ids)

def main():
  acc = [sum(p[0:i+1]) for i in xrange(len(p))]
  print n, p, acc
    
  actions = []
  for cfg in cfgs:
    actions.append(Action(cfg))
    
  for action in actions:
    action.create()

  time.sleep(5)

  for i in xrange(n):
    r = random.random()
    k = 0
    while r > acc[k]: k += 1
    print 'invoking action no.{} {}'.format(k, actions[k].name)
    actions[k].invoke() 
    time.sleep(5) 
  
  for action in actions:
    while action.getAllResult() > 0: time.sleep(10)

  with open('result.pkl', 'w') as fid:
    result = [action.results for action in actions]
    print result
    pickle.dump(result, fid)

  for action in actions:
    action.delete()

if __name__ == '__main__':
  main()
