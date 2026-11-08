#!/usr/bin/env python3
"""Dedicated actual business-source lane with fixed isolated target and cleanup ownership."""
import sys

import risk_capture


def main():
    return risk_capture.main(risk_capture.SOURCE)


if __name__ == "__main__":
    sys.exit(main())
