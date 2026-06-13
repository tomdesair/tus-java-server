import sys
import xml.etree.ElementTree as ET
import argparse

def parse_args():
    parser = argparse.ArgumentParser(description="Check Jacoco coverage limits and report uncovered lines.")
    parser.add_argument("--xml", required=True, help="Path to jacoco.xml")
    parser.add_argument("--limit", type=float, default=95.0, help="Minimum line coverage percentage (0-100)")
    return parser.parse_args()

def main():
    args = parse_args()

    try:
        tree = ET.parse(args.xml)
    except Exception as e:
        print(f"Error parsing Jacoco XML report at {args.xml}: {e}")
        print("Please ensure you run 'mvn test' or 'mvn verify' first to generate coverage reports.")
        sys.exit(1)

    root = tree.getroot()

    # 1. Get overall line coverage
    total_missed = 0
    total_covered = 0
    for counter in root.findall("counter"):
        if counter.attrib.get("type") == "LINE":
            total_missed = int(counter.attrib.get("missed", 0))
            total_covered = int(counter.attrib.get("covered", 0))
            break

    total = total_missed + total_covered
    if total == 0:
        print("No line coverage data found in report.")
        sys.exit(0)

    covered_ratio = total_covered / total
    covered_pct = covered_ratio * 100.0

    print("==========================================================")
    print("                   JACOCO COVERAGE REPORT                 ")
    print("==========================================================")
    print(f"Overall Line Coverage: {covered_pct:.2f}% (Required: {args.limit:.2f}%)")
    print(f"Covered Lines: {total_covered}, Missed Lines: {total_missed}, Total Lines: {total}")
    print("----------------------------------------------------------")

    # 2. Find all uncovered files and lines
    uncovered_files = []

    for pkg in root.findall("package"):
        pkg_name = pkg.attrib.get("name", "")
        for sf in pkg.findall("sourcefile"):
            sf_name = sf.attrib.get("name", "")
            full_path = f"src/main/java/{pkg_name}/{sf_name}"

            missed_lines = []
            partially_covered_lines = []

            for line in sf.findall("line"):
                nr = int(line.attrib.get("nr", 0))
                mi = int(line.attrib.get("mi", 0)) # missed instructions
                mb = int(line.attrib.get("mb", 0)) # missed branches
                ci = int(line.attrib.get("ci", 0)) # covered instructions
                cb = int(line.attrib.get("cb", 0)) # covered branches

                if mi > 0 and ci == 0:
                    # Line not covered at all
                    missed_lines.append(nr)
                elif (mi > 0 and ci > 0) or (mb > 0 and cb > 0):
                    # Line partially covered
                    partially_covered_lines.append(nr)

            if missed_lines or partially_covered_lines:
                uncovered_files.append({
                    "file": full_path,
                    "missed": missed_lines,
                    "partial": partially_covered_lines
                })

    if uncovered_files:
        print("Uncovered / Partially Covered Files and Lines:")
        for uf in uncovered_files:
            file_path = uf["file"]
            print(f"\n📄 {file_path}:")
            if uf["missed"]:
                print(f"   ❌ Uncovered lines: {', '.join(map(str, uf['missed']))}")
            if uf["partial"]:
                print(f"   ⚠️  Partially covered lines: {', '.join(map(str, uf['partial']))}")
    else:
        print("🎉 100% of all lines are fully covered by tests!")

    print("==========================================================")

    if covered_pct < args.limit:
        print(f"❌ FAIL: Line coverage is below threshold of {args.limit:.2f}%!")
        sys.exit(1)
    else:
        print("✅ SUCCESS: Coverage threshold check passed.")
        sys.exit(0)

if __name__ == "__main__":
    main()
