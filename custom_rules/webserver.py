from http.server import BaseHTTPRequestHandler, HTTPServer
import sys
import time

timeToSleep = 0
if len(sys.argv) > 1:
    timeToSleep = int(sys.argv[1])
    print ("going to sleep for %d seconds per request" % timeToSleep)

PORT = 1234
IPADDR = "0.0.0.0"

class testServer(BaseHTTPRequestHandler):
 
  def do_GET(self):
        time.sleep(timeToSleep)
        self.send_response(200)
        self.send_header('Content-type','text/html')
        self.end_headers()
        self.wfile.write(bytes("this is a test message\n", "utf8"))
 
server = HTTPServer((IPADDR, PORT), testServer)
print("running webserver on %s:%d" % (IPADDR, PORT))
server.serve_forever()
