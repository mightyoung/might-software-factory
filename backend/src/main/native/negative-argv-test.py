import subprocess,sys
binary=sys.argv[1]
for args in [[],["launch"],["launch","../bad","0","scan-0","0"],["inspect","fixed","1","scan-0","2"],["--scanner","/bin/sh"],["launch","fixed","00000000-0000-0000-0000-000000000001","scan-0","00000000-0000-0000-0000-000000000002","extra"]]:
    result=subprocess.run([binary,*args],input=b"",capture_output=True,timeout=3,check=False)
    assert result.returncode!=0 and not result.stdout and not result.stderr,args
print("native closed argument negatives: PASS")
