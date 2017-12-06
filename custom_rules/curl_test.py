import requests
def main(args):
        x = ""
        for i in range(6):
		try:
			r = requests.get('http://10.0.0.9:1234')
			x = x + r.text
		except Exception as e:
			return {"a": e.message}
        return {"x": x}
