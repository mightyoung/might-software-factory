#!/usr/bin/env python3
"""Dedicated owned-registry lane; reuse the same ownership and cleanup engine."""
import sys

import risk_capture


def main():
    return risk_capture.main(risk_capture.REGISTRY)


if __name__ == "__main__":
    sys.exit(main())
