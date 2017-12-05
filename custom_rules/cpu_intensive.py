import time
def main(args):
	num = int(args.get("t", "1"))
        start = time.time()
        iterations = 0
        x = 10
        N = 10000000
        while iterations < num*N:
            x = (x*x) % 13
            iterations += 1
        end = time.time()
        resultStr = "iterations was %d, time was %f" % (iterations, end - start)
	return {"result": resultStr }
#print(main({"t": "10"}))
