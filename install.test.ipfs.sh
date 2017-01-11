#!/bin/bash
version=$1
bootstrap=$2
arch="linux-amd64"
if [ "$version" == "" ]; then
    echo "Usage: install.ipfs.sh v0.4.4 [/ip4/172.22.2.24/tcp4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ]"
else
    # test if ipfs executable is already present
    present="$(ls ./ipfs)"
    install=true
    if [ "$present" != "" ]; then
	existing=v"$(./ipfs version -n)"
	echo "Version present: "$existing
	if [ "$existing" != "$version" ]; then
	    echo "Different version present, reinstalling..."
	else
	    install=false
	fi
    fi
    if [ $install == true ]; then
	wget https://dist.ipfs.io/go-ipfs/$version/go-ipfs_"$version"_$arch.tar.gz &&
	rm -rf go-ipfs &&
	tar -xvzf go-ipfs_"$version"_$arch.tar.gz &&
	cp go-ipfs/ipfs . &&
	rm -rf go-ipfs &&
	if [ "$present" == "" ]; then
	    ./ipfs init -e
	fi &&
	./ipfs config Datastore.Path `pwd`/datastore &&
	./ipfs bootstrap rm all &&
	if [ "$bootstrap" != "" ]; then
	    ./ipfs bootstrap add $bootstrap
	fi
    else
	echo "Existing IPFS version is correct"
    fi
fi
