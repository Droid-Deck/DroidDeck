#!/bin/sh
set -eu
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
dpkg --add-architecture amd64
sed -i '/^Architectures:/d; /^Types:/a Architectures: arm64' /etc/apt/sources.list.d/ubuntu.sources
cat > /etc/apt/sources.list.d/amd64.sources <<'EOF'
Types: deb
Architectures: amd64
URIs: https://mirrors.aliyun.com/ubuntu/
Suites: resolute resolute-updates
Components: main universe
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
if [ ! -f /opt/cross/download/gcc-15-aarch64-linux-gnu_15.2.0-16ubuntu1cross1_amd64.deb ]; then
    apt-get update -qq
fi
mkdir -p /opt/cross/download
cd /opt/cross/download
apt-get download g++-15-aarch64-linux-gnu:amd64 gcc-15-aarch64-linux-gnu:amd64 \
    gcc-15-aarch64-linux-gnu-base:amd64 cpp-15-aarch64-linux-gnu:amd64 \
    libc6:amd64 libstdc++6:amd64 libgcc-s1:amd64 libgmp10:amd64 libisl23:amd64 \
    libmpc3:amd64 libmpfr6:amd64 libzstd1:amd64 zlib1g:amd64
for package in *.deb; do dpkg-deb -x "$package" /opt/cross; done
mkdir -p /lib64
ln -sf /opt/cross/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2 /lib64/ld-linux-x86-64.so.2
export LD_LIBRARY_PATH=/opt/cross/usr/lib/x86_64-linux-gnu
file /opt/cross/usr/bin/aarch64-linux-gnu-g++-15
/opt/cross/usr/bin/aarch64-linux-gnu-g++-15 --version
for object in /usr/lib/gcc/aarch64-linux-gnu/15/*.o /usr/lib/gcc/aarch64-linux-gnu/15/libgcc* \
              /usr/lib/gcc/aarch64-linux-gnu/15/libstdc++*; do
    ln -sf "$object" "/opt/cross/usr/lib/gcc-cross/aarch64-linux-gnu/15/$(basename "$object")"
done
cat > /opt/cross/cxx <<'EOF'
#!/bin/sh
export LD_LIBRARY_PATH=/opt/cross/usr/lib/x86_64-linux-gnu
exec /opt/cross/usr/bin/aarch64-linux-gnu-g++-15 -fno-use-linker-plugin -isystem /usr/include/c++/15 \
    -isystem /usr/lib/gcc/aarch64-linux-gnu/15/include \
    -isystem /usr/include/aarch64-linux-gnu/c++/15 -B/usr/lib/aarch64-linux-gnu/ \
    -L/usr/lib/gcc/aarch64-linux-gnu/15 "$@"
EOF
cat > /opt/cross/cc <<'EOF'
#!/bin/sh
export LD_LIBRARY_PATH=/opt/cross/usr/lib/x86_64-linux-gnu
exec /opt/cross/usr/bin/aarch64-linux-gnu-gcc-15 -fno-use-linker-plugin -B/usr/lib/aarch64-linux-gnu/ \
    -isystem /usr/lib/gcc/aarch64-linux-gnu/15/include \
    -L/usr/lib/gcc/aarch64-linux-gnu/15 "$@"
EOF
chmod +x /opt/cross/cc /opt/cross/cxx
printf '#include <iostream>\nint main(){std::cout << "arm64 cross ready\\n";}\n' > /opt/cross/probe.cpp
/opt/cross/cxx /opt/cross/probe.cpp -o /opt/cross/probe
file /opt/cross/probe
/opt/cross/probe
