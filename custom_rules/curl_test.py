import requests
def main(args):
	try:
		r = requests.get('http://10.0.0.7:1234')
		return {"a": r.text}
	except Exception as e:
		return {"a": e.message}
print main({})
