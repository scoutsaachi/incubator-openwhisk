def main(args):
	name = args.get("name", "stranger")
	greeting = "Hello " + name + "!"
	values = []
	for i in range(0,10000):
		values.append(i)
	summed = 0
	for i in range(0, 1000000):
		summed = (i*i + summed) % len(values)
	return {"greeting": greeting + str(summed)}
