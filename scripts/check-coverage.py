import sys
import xml.etree.ElementTree as ET
import argparse
import subprocess

def parse_args():
    parser = argparse.ArgumentParser(description="Check Jacoco coverage limits and report uncovered lines.")
    parser.add_argument("--xml", required=True, nargs="+", help="Path(s) to jacoco.xml files")
    parser.add_argument("--limit", type=float, default=95.0, help="Minimum line coverage percentage (0-100)")
    parser.add_argument("--compare-branch", help="Compare against a git branch and check coverage of new/modified lines only")
    return parser.parse_args()

def group_ranges(nums):
    if not nums:
        return ""
    nums = sorted(list(set(nums)))
    ranges = []
    start = nums[0]
    end = nums[0]
    for n in nums[1:]:
        if n == end + 1:
            end = n
        else:
            if start == end:
                ranges.append(str(start))
            else:
                ranges.append(f"{start}-{end}")
            start = n
            end = n
    if start == end:
        ranges.append(str(start))
    else:
        ranges.append(f"{start}-{end}")
    return ", ".join(ranges)

def get_modified_lines(compare_branch):
    try:
        result = subprocess.run(
            ["git", "diff", compare_branch, "--", "src/main/java"],
            capture_output=True,
            text=True,
            check=True
        )
    except Exception as e:
        print(f"Warning: Could not run git diff against {compare_branch}: {e}")
        return None

    diff_output = result.stdout
    modified_lines = {} # file_path -> set of line numbers

    current_file = None
    current_line = 0

    for line in diff_output.splitlines():
        if line.startswith("diff --git"):
            parts = line.split(" ")
            if len(parts) >= 4:
                b_path = parts[3]
                if b_path.startswith("b/"):
                    current_file = b_path[2:]
                else:
                    current_file = b_path
        elif line.startswith("@@"):
            try:
                hunk_info = line.split("@@")[1].strip()
                parts = hunk_info.split(" ")
                new_info = [p for p in parts if p.startswith("+")][0]
                new_start = int(new_info[1:].split(",")[0])
                current_line = new_start
            except Exception:
                pass
        elif current_file:
            if line.startswith("+") and not line.startswith("+++"):
                if current_file not in modified_lines:
                    modified_lines[current_file] = set()
                modified_lines[current_file].add(current_line)
                current_line += 1
            elif line.startswith("-") and not line.startswith("---"):
                pass
            else:
                if line.startswith(" ") or line.startswith("\\"):
                    if line.startswith(" "):
                        current_line += 1

    return modified_lines

def main():
    args = parse_args()

    parsed_trees = []
    for xml_path in args.xml:
        try:
            tree = ET.parse(xml_path)
            parsed_trees.append((xml_path, tree))
        except Exception as e:
            print(f"Warning: Could not parse Jacoco XML report at {xml_path}: {e}")

    if not parsed_trees:
        print("Error: No valid Jacoco XML reports could be parsed.")
        print("Please ensure you run 'mvn test' or 'mvn verify' first to generate coverage reports.")
        sys.exit(1)

    # Aggregate coverage data across all reports:
    # coverage_data: full_path -> { line_nr -> { "mi": [], "ci": [], "mb": [], "cb": [] } }
    coverage_data = {}

    for xml_path, tree in parsed_trees:
        root = tree.getroot()
        for pkg in root.findall("package"):
            pkg_name = pkg.attrib.get("name", "")
            for sf in pkg.findall("sourcefile"):
                sf_name = sf.attrib.get("name", "")
                full_path = f"src/main/java/{pkg_name}/{sf_name}"

                if full_path not in coverage_data:
                    coverage_data[full_path] = {}

                for line in sf.findall("line"):
                    nr = int(line.attrib.get("nr", 0))
                    mi = int(line.attrib.get("mi", 0))
                    mb = int(line.attrib.get("mb", 0))
                    ci = int(line.attrib.get("ci", 0))
                    cb = int(line.attrib.get("cb", 0))

                    if nr not in coverage_data[full_path]:
                        coverage_data[full_path][nr] = {
                            "mi": [], "ci": [], "mb": [], "cb": []
                        }

                    coverage_data[full_path][nr]["mi"].append(mi)
                    coverage_data[full_path][nr]["ci"].append(ci)
                    coverage_data[full_path][nr]["mb"].append(mb)
                    coverage_data[full_path][nr]["cb"].append(cb)

    # Calculate overall stats
    total_covered = 0
    total_missed = 0

    for full_path, file_lines in coverage_data.items():
        for nr, line_info in file_lines.items():
            best_mi = min(line_info["mi"])
            best_ci = max(line_info["ci"])
            if best_ci > 0:
                total_covered += 1
            elif best_mi > 0:
                total_missed += 1

    total = total_covered + total_missed
    if total == 0:
        print("No line coverage data found in report.")
        sys.exit(0)

    covered_pct = (total_covered / total) * 100.0

    compare_branch = args.compare_branch
    if compare_branch == "":
        compare_branch = None

    modified_lines_filter = None
    if compare_branch:
        modified_lines_filter = get_modified_lines(compare_branch)
        if modified_lines_filter is None:
            print(f"Failed to get modified lines against {compare_branch}. Aborting.")
            sys.exit(1)

    # Find all uncovered files and lines
    uncovered_files = []

    for full_path in sorted(coverage_data.keys()):
        # If we are filtering by modified lines, check if this file is modified
        if modified_lines_filter is not None and full_path not in modified_lines_filter:
            continue

        missed_lines = []
        partially_covered_lines = []

        file_lines = coverage_data[full_path]
        for nr in sorted(file_lines.keys()):
            # If we are filtering by modified lines, check if this specific line is modified
            if modified_lines_filter is not None:
                if nr not in modified_lines_filter[full_path]:
                    continue

            line_info = file_lines[nr]
            best_mi = min(line_info["mi"])
            best_ci = max(line_info["ci"])
            best_mb = min(line_info["mb"])
            best_cb = max(line_info["cb"])

            if best_mi > 0 and best_ci == 0:
                missed_lines.append(nr)
            elif (best_mi > 0 and best_ci > 0) or (best_mb > 0 and best_cb > 0):
                partially_covered_lines.append(nr)

        if missed_lines or partially_covered_lines:
            uncovered_files.append({
                "file": full_path,
                "missed": missed_lines,
                "partial": partially_covered_lines
            })

    if modified_lines_filter is not None:
        print("==========================================================")
        print("             JACOCO DIFF COVERAGE REPORT                  ")
        print("==========================================================")
        print(f"Comparing against branch: {compare_branch}")
        print("Checking only lines modified/added in this branch.")
        print("----------------------------------------------------------")
        if uncovered_files:
            print("Uncovered / Partially Covered Modified Lines:")
            for uf in uncovered_files:
                file_path = uf["file"]
                print(f"\n📄 {file_path}:")
                if uf["missed"]:
                    print(f"   ❌ Uncovered lines: {group_ranges(uf['missed'])}")
                if uf["partial"]:
                    print(f"   ⚠️  Partially covered lines: {group_ranges(uf['partial'])}")

            has_missed = any(uf["missed"] for uf in uncovered_files)
            print("==========================================================")
            if has_missed:
                print("❌ FAIL: Some modified lines are not covered by unit tests!")
                sys.exit(1)
            else:
                print("🎉 All new and modified lines are covered by unit tests!")
                print("⚠️  Note: Some lines have partial branch coverage (see report above).")
                sys.exit(0)
        else:
            print("🎉 All new and modified lines are 100% covered by unit tests!")
            print("==========================================================")
            sys.exit(0)
    else:
        print("==========================================================")
        print("                   JACOCO COVERAGE REPORT                 ")
        print("==========================================================")
        print(f"Overall Line Coverage: {covered_pct:.2f}% (Required: {args.limit:.2f}%)")
        print(f"Covered Lines: {total_covered}, Missed Lines: {total_missed}, Total Lines: {total}")
        print("----------------------------------------------------------")
        if uncovered_files:
            print("Uncovered / Partially Covered Files and Lines:")
            for uf in uncovered_files:
                file_path = uf["file"]
                print(f"\n📄 {file_path}:")
                if uf["missed"]:
                    print(f"   ❌ Uncovered lines: {group_ranges(uf['missed'])}")
                if uf["partial"]:
                    print(f"   ⚠️  Partially covered lines: {group_ranges(uf['partial'])}")
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
