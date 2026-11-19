#!/usr/bin/env python3
"""Fixed isolated identity reauthentication acceptance; never accepts a LAN target."""
import sys

import risk_capture


def main():
    return risk_capture.main(risk_capture.REAUTH)


if __name__ == "__main__":
    sys.exit(main())
