sudo ansible-playbook couchdb.yml
sudo ansible-playbook initdb.yml
sudo ansible-playbook wipe.yml
sudo ansible-playbook apigateway.yml
sudo ansible-playbook openwhisk.yml
sudo ansible-playbook postdeploy.yml
