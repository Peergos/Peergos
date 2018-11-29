#!/bin/bash
command=$1

if [ "$command" == "" ]; then
    echo 'Usage: data.service.sh command [$arg1] [$arg2] [$arg3]'
    echo "command [args...] in {"
    echo '                        install $version $target_dir $repo_dir [$bootstrap_node_multiaddr]'
    echo '                        restart $repo_dir $target_dir'
    echo "                        stop"
    echo '                        uninstall $repo_dir $target_dir'
    echo "                     }"
    echo "      e.g. data.service.sh uninstall . ."
    echo "      e.g. data.service.sh restart . ."
    echo "      e.g. data.service.sh stop"
    echo "      e.g. data.service.sh install v0.4.4 . . [/ip4/172.22.2.24/tcp4001/ipfs/QmaCpDMGvV2BGHeYERUEnRQAwe3N8SzbUtfsmvsqQLuvuJ]"
    echo "      e.g. data.service.sh install v0.4.4 /usr/local/bin ."
    exit 0
fi

if [ "$command" == "restart" ]; then
    repo_dir=$2
    target_dir=$3

    export IPFS_PATH=$repo_dir/.ipfs

    pgrep ipfs && echo "ipfs already running" && exit 0
    pgrep ipfs || nohup "$target_dir/"ipfs daemon > /dev/null 2>&1 &
    echo "ipfs started"
    exit 0
fi

if [ "$command" == "stop" ]; then
    kill `pgrep ipfs`
    echo "ipfs stopped"
    exit 0
fi

if [ "$command" == "uninstall" ]; then
    repo_dir=$2
    target_dir=$3
    rm -rf "$repo_dir/.ipfs/"
    rm "$target_dir/ipfs"
    exit 0
fi

if [ "$command" == "install" ]; then
    version=$2
    target_dir=$3
    repo_dir=$4
    bootstrap=$5
    arch="linux-amd64"

    # test if ipfs executable is already present
    present="$(ls $target_dir/ipfs)"
    install=true

    if [ "$present" != "" ]; then
        existing=v"$($target_dir/ipfs version -n)"
        echo "Version present: "$existing
        if [ "$existing" != "$version" ]; then
            echo "Different version present, reinstalling..."
        else
            install=false
        fi
    fi
    if [ $install == true ]; then
        export IPFS_PATH=$repo_dir/.ipfs
        wget https://dist.ipfs.io/go-ipfs/$version/go-ipfs_"$version"_$arch.tar.gz &&
            rm -rf go-ipfs &&
            tar_filename=go-ipfs_"$version"_$arch.tar.gz &&
            tar -xvzf go-ipfs_"$version"_$arch.tar.gz &&
            cp go-ipfs/ipfs $target_dir/ &&
            rm -rf go-ipfs &&
            rm $tar_filename &&

            if [ "$present" == "" ]; then
                echo "initializing ipfs with IPFS_PATH=$IPFS_PATH"
                ./ipfs init -e
            fi &&

                ./ipfs bootstrap rm all &&
                if [ "$bootstrap" != "" ]; then
                    ./ipfs bootstrap add $bootstrap
                fi
                echo "IPFS installation successful!"
            else
                echo "Existing IPFS version is correct"
            fi
            exit 0
        fi
        echo "Unknown command: $command"
