"""派生一个延迟写标记的孙子进程，用于验证整组终止。"""
import subprocess
import sys
import time

marker = sys.argv[1]
subprocess.Popen([
    sys.executable,
    "-c",
    "import time,sys; time.sleep(3); open(sys.argv[1], 'w', encoding='utf-8').write('late')",
    marker,
])
time.sleep(60)
