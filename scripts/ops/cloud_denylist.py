#!/usr/bin/env python3
"""Regenerate the ingress denylist of cloud address space the scraper fleets run from.

The fleets that set off RewayaatV2TrafficSurge send a browser's user agent (Windows or Mac
Chrome, the version rotated per request) from thousands of addresses, a request or two each,
so the per-address rate limit never sees them. About a third of what they send comes through
residential proxies, which no address list can stop. The rest comes from a handful of cloud
providers whose ranges no reader of this site browses from, and that part is blocked here,
at the ingress, before the application is asked.

The list is every IPv4 prefix the networks below announce, read from RIPEstat and collapsed.
It is written into k8s/ingress.yaml's denylist-source-range annotation, which keeps the
two ranges that were blocked before this script existed.

Usage:
    python3 scripts/ops/cloud_denylist.py            # print what would change
    python3 scripts/ops/cloud_denylist.py --write    # rewrite the annotation
"""
import argparse
import ipaddress
import json
import pathlib
import re
import sys
import urllib.request

# Measured on 2026-11-05/06: of the fleet's requests that were not already refused, 70% came
# from these networks, Alibaba's alone 60%.
NETWORKS = {
    45102: "Alibaba Cloud (international)",
    37963: "Alibaba Cloud (China)",
    132203: "Tencent Cloud (international)",
    45090: "Tencent Cloud (China)",
    136907: "Huawei Cloud",
    55990: "Huawei Cloud (China)",
    150436: "BytePlus",
}
# Blocked by hand before the script; kept whether or not an ASN still announces them.
ALWAYS = ["43.172.0.0/15", "47.79.0.0/16"]
# Cut back out of the list. PetalBot, the crawler of Huawei's Petal Search (the default
# search on Huawei phones), crawls from this range of AS136907: 6,034 requests in the week to
# 2026-11-06, from 16 addresses. Everything else from the range in that week came to 318.
EXEMPT = ["114.119.128.0/18"]

INGRESS = pathlib.Path(__file__).resolve().parents[2] / "k8s" / "ingress.yaml"
ANNOTATION = "nginx.ingress.kubernetes.io/denylist-source-range"


def announced(asn):
    url = f"https://stat.ripe.net/data/announced-prefixes/data.json?resource=AS{asn}"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = json.load(response)
    return [ipaddress.ip_network(p["prefix"]) for p in data["data"]["prefixes"]
            if ":" not in p["prefix"]]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true", help="rewrite k8s/ingress.yaml")
    args = parser.parse_args()

    nets = [ipaddress.ip_network(p) for p in ALWAYS]
    for asn, name in NETWORKS.items():
        found = announced(asn)
        print(f"AS{asn:<7} {name:32} {len(found):5} prefixes", file=sys.stderr)
        nets += found
    collapsed = list(ipaddress.collapse_addresses(nets))
    for hole in map(ipaddress.ip_network, EXEMPT):
        collapsed = [part for net in collapsed
                     for part in (net.address_exclude(hole) if hole.subnet_of(net)
                                  else [] if net.subnet_of(hole) else [net])]
    collapsed = list(ipaddress.collapse_addresses(collapsed))
    value = ",".join(str(n) for n in collapsed)
    print(f"{len(collapsed)} ranges, {sum(n.num_addresses for n in collapsed):,} addresses",
          file=sys.stderr)

    text = INGRESS.read_text()
    pattern = re.compile(r'(    ' + re.escape(ANNOTATION) + r': )"[^"]*"')
    if not pattern.search(text):
        sys.exit(f"{ANNOTATION} not found in {INGRESS}")
    updated = pattern.sub(lambda m: m.group(1) + '"' + value + '"', text, count=1)
    if updated == text:
        print("no change", file=sys.stderr)
        return
    if args.write:
        INGRESS.write_text(updated)
        print(f"wrote {INGRESS}", file=sys.stderr)
    else:
        print("would change the annotation; run with --write", file=sys.stderr)


if __name__ == "__main__":
    main()
