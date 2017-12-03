ansible-playbook postdeploy.yml -e mode=clean
ansible-playbook openwhisk.yml -e mode=clean
ansible-playbook apigateway.yml -e mode=clean
ansible-playbook wipe.yml -e mode=clean
ansible-playbook initdb.yml -e mode=clean
ansible-playbook couchdb.yml -e mode=clean
