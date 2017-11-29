import sys, os
import time
import json
import subprocess
import shlex

import threading
numCalls = 60
method1 = [(0,0) for i in range(0,numCalls/2)]
method2 = [(0,0) for i in range(0,numCalls/2)]

def callMethod1(i):
  print "starting a method 1"
  start = time.time()
  proc = os.system("../bin/wsk -i action invoke throughput --result --param name World")
  end = time.time()
  method1[i] = (start, end-start)

def callMethod2(i):
  print "starting a method 2"
  start = time.time()
  proc = os.system("../bin/wsk -i action invoke /whisk.system/samples/curl -p payload \"http://www.example.org/\" --result")
  end = time.time()
  method2[i] = (start, end-start)

threads = []
method1Counter = 0
method2Counter = 0
for i in range(numCalls):
  if i % 2 == 0:
  	t = threading.Thread(target=callMethod1, args=(method1Counter,))
        method1Counter += 1
  	threads.append(t)
  	t.start()
  else:
        t = threading.Thread(target=callMethod2, args=(method2Counter,))
        method2Counter += 1
        threads.append(t)
        t.start()
  time.sleep(1)

for t in threads:
  t.join()

print method1
print method2
