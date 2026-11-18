CC = gcc
OUT_DIR ?= /tmp/msf-scanner-broker-build
CFLAGS = -std=c11 -O2 -g -Wall -Wextra -Werror -Wconversion -Wshadow -Wformat=2 -Wformat-security -fstack-protector-strong -D_FORTIFY_SOURCE=3 -fPIE
CPPFLAGS ?=
LDLIBS = -l:libcrypto.so.3 -ldl
LDFLAGS = -pie -Wl,-z,relro,-z,now,-z,noexecstack
.PHONY: scanner-broker protocol-test crypto-smoke
scanner-broker:
	mkdir -p "$(OUT_DIR)"
	$(CC) $(CPPFLAGS) $(CFLAGS) msf-scanner-broker.c $(LDFLAGS) -Wl,-Map,"$(OUT_DIR)/msf-scanner-broker.map" -o "$(OUT_DIR)/msf-scanner-broker" $(LDLIBS)
	$(CC) --version > "$(OUT_DIR)/compiler.txt"
	uname -a > "$(OUT_DIR)/kernel.txt"
	getconf GNU_LIBC_VERSION > "$(OUT_DIR)/libc.txt"
	sha256sum msf-scanner-broker.c "$(OUT_DIR)/msf-scanner-broker" > "$(OUT_DIR)/hashes.txt"
	readelf -h -l -d "$(OUT_DIR)/msf-scanner-broker" > "$(OUT_DIR)/elf.txt"
protocol-test: scanner-broker
	$(CC) $(CPPFLAGS) $(CFLAGS) protocol-test.c $(LDFLAGS) -o "$(OUT_DIR)/protocol-test" $(LDLIBS)
	"$(OUT_DIR)/protocol-test"
	$(CC) $(CPPFLAGS) $(CFLAGS) crypto-failure-test.c $(LDFLAGS) -o "$(OUT_DIR)/crypto-failure-test" $(LDLIBS)
	"$(OUT_DIR)/crypto-failure-test"
	python3 -B negative-argv-test.py "$(OUT_DIR)/msf-scanner-broker"
crypto-smoke:
	mkdir -p "$(OUT_DIR)"
	$(CC) $(CPPFLAGS) $(CFLAGS) crypto-smoke.c $(LDFLAGS) -o "$(OUT_DIR)/crypto-smoke" $(LDLIBS)
