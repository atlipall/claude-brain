"""Writes the custom plugin repository file (updatePlugins.xml) that JetBrains IDEs poll for Tether updates.

The plugin's id, name, vendor, description and compatible builds are read from the plugin.xml inside the
built zip, so the repository entry always matches the published build.

Usage: plugin_repository.py --zip build/distributions/tether-0.1.5.zip --url <download url>
                            [--notes <file with one change per line, plain or as a Markdown list>] > updatePlugins.xml
"""

import argparse
import io
import sys
import xml.etree.ElementTree as ET
import zipfile
from html import escape


def read_plugin_xml(plugin_zip: str) -> ET.Element:
    """Returns the root of META-INF/plugin.xml from the plugin jar inside the distribution zip."""
    with zipfile.ZipFile(plugin_zip) as distribution:
        for name in distribution.namelist():
            if name.endswith(".jar") and "/lib/" in name:
                with zipfile.ZipFile(io.BytesIO(distribution.read(name))) as jar:
                    if "META-INF/plugin.xml" in jar.namelist():
                        return ET.fromstring(jar.read("META-INF/plugin.xml"))
    sys.exit(f"No jar with META-INF/plugin.xml in {plugin_zip}")


def change_notes_html(notes_file: str | None) -> str:
    """Turns a file with one change per line, optionally as Markdown "- " list items, into an HTML list.

    Returns "" without a file or when it lists no changes.
    """
    if notes_file is None:
        return ""
    with open(notes_file, encoding="utf-8") as notes:
        changes = [line.strip().removeprefix("- ").strip() for line in notes if line.strip()]
    if not changes:
        return ""
    return "<ul>" + "".join(f"<li>{escape(change)}</li>" for change in changes) + "</ul>"


def compatible_builds(plugin: ET.Element) -> dict[str, str]:
    """Returns the since-build and until-build attributes the plugin declares, leaving out unset ones."""
    idea_version = plugin.find("idea-version")
    if idea_version is None:
        return {}
    return {key: value for key in ("since-build", "until-build") if (value := idea_version.get(key))}


def required_text(plugin: ET.Element, tag: str) -> str:
    """Returns the text of a plugin.xml element the repository entry can't do without, or exits naming it."""
    text = plugin.findtext(tag)
    if not text:
        sys.exit(f"plugin.xml has no <{tag}>")
    return text


def repository_xml(plugin: ET.Element, download_url: str, change_notes: str) -> str:
    """Builds the updatePlugins.xml document with a single entry for the given plugin build."""
    repository = ET.Element("plugins")
    entry = ET.SubElement(repository, "plugin", id=required_text(plugin, "id"), url=download_url,
                          version=required_text(plugin, "version"))
    ET.SubElement(entry, "idea-version", compatible_builds(plugin))
    ET.SubElement(entry, "name").text = plugin.findtext("name")
    vendor = plugin.find("vendor")
    if vendor is not None:
        ET.SubElement(entry, "vendor", {"url": vendor.get("url", "")}).text = vendor.text
    ET.SubElement(entry, "description").text = (plugin.findtext("description") or "").strip()
    if change_notes:
        ET.SubElement(entry, "change-notes").text = change_notes
    ET.indent(repository)
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(repository, encoding="unicode") + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--zip", required=True, help="the built plugin distribution zip")
    parser.add_argument("--url", required=True, help="where IDEs download that zip from")
    parser.add_argument("--notes", help="a file with one change per line, plain or as a Markdown list, shown in the IDE's update dialog")
    args = parser.parse_args()
    sys.stdout.write(repository_xml(read_plugin_xml(args.zip), args.url, change_notes_html(args.notes)))


if __name__ == "__main__":
    main()
