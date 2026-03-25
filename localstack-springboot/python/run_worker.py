import os
import subprocess
import time


SLEEP_SECONDS = int(os.getenv("WORKER_INTERVAL_SECONDS", "60"))


def run_once():
    cmd = ["python", "python/autoencoder_inference_localstack.py"]
    process = subprocess.run(cmd, check=False)
    return process.returncode


def main():
    while True:
        code = run_once()
        print(f"autoencoder worker cycle exit={code}")
        time.sleep(SLEEP_SECONDS)


if __name__ == "__main__":
    main()
