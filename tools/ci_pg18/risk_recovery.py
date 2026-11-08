#!/usr/bin/env python3
"""Fixed isolated native listener and new-JVM recovery acceptance."""
import sys
import risk_capture


def main():
    return risk_capture.main(risk_capture.RECOVERY)


if __name__ == "__main__":
    sys.exit(main())
