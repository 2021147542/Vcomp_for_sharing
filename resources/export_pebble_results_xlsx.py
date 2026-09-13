#!/usr/bin/env python3
"""Export the retained Pebble VComp result JSON files as a styled XLSX workbook."""

import argparse
import json
import math
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.sax.saxutils import escape


WORKLOADS = ["A", "B", "C", "D", "E", "F", "MIXGRAPH"]
SYSTEMS = ["baseline", "virtual"]


def column_name(index):
    result = ""
    while index:
        index, remainder = divmod(index - 1, 26)
        result = chr(65 + remainder) + result
    return result


def string_cell(reference, value, style=0):
    return (f'<c r="{reference}" t="inlineStr" s="{style}">' 
            f'<is><t>{escape(str(value))}</t></is></c>')


def number_cell(reference, value, style=0):
    if value is None or not math.isfinite(float(value)):
        return string_cell(reference, "—", style)
    return f'<c r="{reference}" s="{style}"><v>{value}</v></c>'


def make_sheet(rows, widths, merges=(), freeze_row=0, auto_filter=None):
    row_xml = []
    for row_index, row in enumerate(rows, 1):
        cells = []
        for column_index, cell in enumerate(row, 1):
            if cell is None:
                continue
            reference = f"{column_name(column_index)}{row_index}"
            kind, value, style = cell
            cells.append(string_cell(reference, value, style) if kind == "s"
                         else number_cell(reference, value, style))
        height = ' ht="28" customHeight="1"' if row_index == 1 else ""
        row_xml.append(f'<row r="{row_index}"{height}>{"".join(cells)}</row>')

    columns = "".join(f'<col min="{index}" max="{index}" width="{width}" customWidth="1"/>'
                      for index, width in enumerate(widths, 1))
    pane = (f'<sheetViews><sheetView workbookViewId="0"><pane ySplit="{freeze_row}" '
            f'topLeftCell="A{freeze_row + 1}" activePane="bottomLeft" state="frozen"/>'
            f'</sheetView></sheetViews>') if freeze_row else '<sheetViews><sheetView workbookViewId="0"/></sheetViews>'
    merge_xml = ""
    if merges:
        merge_xml = (f'<mergeCells count="{len(merges)}">' +
                     "".join(f'<mergeCell ref="{item}"/>' for item in merges) +
                     '</mergeCells>')
    filter_xml = f'<autoFilter ref="{auto_filter}"/>' if auto_filter else ""
    return (f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            f'<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
            f'{pane}<cols>{columns}</cols><sheetData>{"".join(row_xml)}</sheetData>'
            f'{filter_xml}{merge_xml}</worksheet>')


def load_records(root):
    load = json.loads((root / "paper_load_result.json").read_text())
    records = {}
    for path in (root / "paper-workloads" / "results").glob("*.json"):
        record = json.loads(path.read_text())
        records[(record["workload"].upper(), record["system"])] = record
    expected = {(workload, system) for workload in WORKLOADS for system in SYSTEMS}
    missing = expected - set(records)
    if missing:
        raise SystemExit(f"missing workload results: {sorted(missing)}")
    return load, records


def loading_rows(load):
    baseline = load["baseline"]
    vcomp = load["vcomp"]
    metrics = [
        ("Loading time", "s", baseline["loading_seconds"], vcomp["loading_seconds"],
         baseline["loading_seconds"] / vcomp["loading_seconds"], "speedup", 6, 8),
        ("Total SST write", "TB", baseline["total_disk_write_bytes"] / 1e12,
         vcomp["total_disk_write_bytes"] / 1e12,
         1 - vcomp["total_disk_write_bytes"] / baseline["total_disk_write_bytes"],
         "reduction", 6, 7),
        ("Write amplification", "×", baseline["write_amplification"],
         vcomp["write_amplification"],
         1 - vcomp["write_amplification"] / baseline["write_amplification"],
         "reduction", 6, 7),
        ("Final DB size", "GB", baseline["final_db_bytes"] / 1e9,
         vcomp["final_db_bytes"] / 1e9,
         (vcomp["final_db_bytes"] - baseline["final_db_bytes"]) / baseline["final_db_bytes"],
         "delta", 6, 7),
        ("SST count", "count", baseline["sst_count"], vcomp["sst_count"],
         (vcomp["sst_count"] - baseline["sst_count"]) / baseline["sst_count"],
         "delta", 5, 7),
        ("Average SST size", "MB", baseline["average_sst_bytes"] / 1e6,
         vcomp["average_sst_bytes"] / 1e6,
         (vcomp["average_sst_bytes"] - baseline["average_sst_bytes"]) / baseline["average_sst_bytes"],
         "delta", 6, 7),
    ]
    rows = [
        [("s", "Pebble VComp — 1 TiB loading results", 1)],
        [("s", "Dataset", 9), ("s", f"{load['dataset_bytes'] / 2**40:.3f} TiB", 0),
         ("s", "KV", 9), ("s", f"{load['key_size']} B key + {load['value_size']} B value", 0)],
        [("s", "WAL / compression", 9), ("s", "disabled / disabled", 0)],
        [],
        [("s", "Metric", 2), ("s", "Unit", 2), ("s", "Baseline", 2),
         ("s", "VComp", 2), ("s", "Comparison", 2), ("s", "Meaning", 2)],
    ]
    for name, unit, base, virtual, comparison, meaning, value_style, comparison_style in metrics:
        rows.append([("s", name, 0), ("s", unit, 0), ("n", base, value_style),
                     ("n", virtual, value_style), ("n", comparison, comparison_style),
                     ("s", meaning, 0)])
    return rows


def workload_rows(records):
    definitions = {
        "throughput_ops_per_second": ("Throughput", "ops/s", 5),
        "point_lookup_latency_p50_us": ("Point lookup p50", "µs", 5),
        "point_lookup_latency_p95_us": ("Point lookup p95", "µs", 5),
        "point_lookup_latency_p99_us": ("Point lookup p99", "µs", 5),
        "disk_read_bytes": ("Disk read", "GB / 5 min", 6),
        "disk_write_bytes": ("Disk write", "MB / 5 min", 6),
    }
    rows = [
        [("s", "Pebble VComp — YCSB A–F and MixGraph", 1)],
        [("s", "Configuration", 9),
         ("s", "48 clients, 5 minutes/system, 32 GiB block cache", 0)],
        [],
        [("s", "Workload", 2), ("s", "Definition", 2), ("s", "Metric", 2),
         ("s", "Unit", 2), ("s", "Baseline", 2), ("s", "VComp", 2),
         ("s", "VComp Δ", 2)],
    ]
    for workload in WORKLOADS:
        baseline = records[(workload, "baseline")]
        vcomp = records[(workload, "virtual")]
        for metric_index, (field, (name, unit, style)) in enumerate(definitions.items()):
            base_value = baseline.get(field)
            virtual_value = vcomp.get(field)
            if field == "disk_read_bytes":
                base_value /= 1e9
                virtual_value /= 1e9
            elif field == "disk_write_bytes":
                base_value /= 1e6
                virtual_value /= 1e6
            delta = ((virtual_value - base_value) / base_value) if base_value else None
            row_style = 3 if metric_index == 0 else 0
            rows.append([("s", "MixGraph" if workload == "MIXGRAPH" else workload, row_style),
                         ("s", baseline["definition"] if metric_index == 0 else "", row_style),
                         ("s", name, row_style), ("s", unit, row_style),
                         ("n", base_value, style), ("n", virtual_value, style),
                         ("n", delta, 7)])
    return rows


def write_workbook(output, loading, workloads):
    timestamp = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    content_types = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>'''
    root_rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>'''
    workbook = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="Loading" sheetId="1" r:id="rId1"/><sheet name="Workloads" sheetId="2" r:id="rId2"/></sheets><calcPr calcId="191029" fullCalcOnLoad="1"/></workbook>'''
    workbook_rels = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>'''
    styles = r'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="4"><numFmt numFmtId="164" formatCode="#,##0.000"/><numFmt numFmtId="165" formatCode="0.00%"/><numFmt numFmtId="166" formatCode="0.00&quot;×&quot;"/><numFmt numFmtId="167" formatCode="#,##0.00"/></numFmts>
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="16"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts>
<fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFD9EAF7"/><bgColor indexed="64"/></patternFill></fill></fills>
<borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9E2F3"/></left><right style="thin"><color rgb="FFD9E2F3"/></right><top style="thin"><color rgb="FFD9E2F3"/></top><bottom style="thin"><color rgb="FFD9E2F3"/></bottom><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="10"><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" vertical="center"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf><xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0"/><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0"/><xf numFmtId="3" fontId="0" fillId="0" borderId="1" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0"/><xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0"/><xf numFmtId="166" fontId="0" fillId="0" borderId="1" xfId="0"/><xf numFmtId="0" fontId="2" fillId="2" borderId="1" xfId="0"/></cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>'''
    core = f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>Pebble VComp 1 TiB results</dc:title><dc:creator>Codex</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">{timestamp}</dcterms:created></cp:coreProperties>'''
    app = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>Microsoft Excel Compatible</Application></Properties>'''

    sheet1 = make_sheet(loading, [24, 14, 18, 18, 18, 16], merges=("A1:F1",),
                        freeze_row=5, auto_filter=f"A5:F{len(loading)}")
    sheet2 = make_sheet(workloads, [13, 34, 24, 16, 18, 18, 16], merges=("A1:G1",),
                        freeze_row=4, auto_filter=f"A4:G{len(workloads)}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("xl/workbook.xml", workbook)
        archive.writestr("xl/_rels/workbook.xml.rels", workbook_rels)
        archive.writestr("xl/styles.xml", styles)
        archive.writestr("xl/worksheets/sheet1.xml", sheet1)
        archive.writestr("xl/worksheets/sheet2.xml", sheet2)
        archive.writestr("docProps/core.xml", core)
        archive.writestr("docProps/app.xml", app)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("db_root", type=Path)
    parser.add_argument("output", type=Path, nargs="?")
    args = parser.parse_args()
    load, records = load_records(args.db_root)
    output = args.output or args.db_root / "paper-figures" / "pebble_vcomp_1tib_results.xlsx"
    write_workbook(output, loading_rows(load), workload_rows(records))
    print(output)


if __name__ == "__main__":
    main()
