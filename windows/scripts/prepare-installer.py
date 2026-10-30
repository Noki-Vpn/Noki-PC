"""Capture the current public agreement for the offline installer."""
import argparse
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.request import Request, urlopen

ORIGIN = "https://noki.ykino.tech"


class LegalDocument(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.parts = []
        self.sections = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        parent = self.stack[-1][1] if self.stack else False
        selected = parent or tag == "h1" or attrs.get("class") in ("legal-hero__meta", "legal-document")
        selected = selected and tag not in ("button", "script", "style")
        self.stack.append((tag, selected))
        if selected:
            if tag in ("p", "h1", "h2", "li", "tr", "br"):
                self.parts.append("\n")
            if tag == "h2":
                self.sections += 1
            if tag == "a" and attrs.get("href", "").startswith("/legal/"):
                self.parts.append(f" {ORIGIN}{attrs['href']} ")
        if tag in ("br", "hr", "img", "meta", "link", "input", "wbr"):
            self.stack.pop()

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                if self.stack[index][1]:
                    if tag in ("p", "h1", "h2", "li", "tr", "details"):
                        self.parts.append("\n\n")
                    elif tag in ("td", "th"):
                        self.parts.append(" | ")
                del self.stack[index:]
                break

    def handle_data(self, data):
        if self.stack and self.stack[-1][1]:
            self.parts.append(data)

    def text(self):
        text = "".join(self.parts)
        text = re.sub(r"[^\S\n]+", " ", text)
        text = re.sub(r"\n *", "\n", text)
        return re.sub(r"\n{3,}", "\n\n", text).strip()


def capture(slug, title):
    url = f"{ORIGIN}/legal/{slug}"
    with urlopen(Request(url, headers={"User-Agent": "Noki-Installer-Packaging"}), timeout=45) as response:
        if response.url.rstrip("/") != url or response.headers.get_content_type() != "text/html":
            raise ValueError(f"Unexpected legal page response: {url}")
        source = response.read().decode("utf-8")
    document = LegalDocument()
    document.feed(source)
    text = document.text()
    if title not in text or "Редакция" not in text or document.sections < 10 or len(text) < 5000:
        raise ValueError(f"Incomplete public legal document: {url}")
    return f"Источник: {url}\n\n{text}\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    # Never silently fall back to a stale local copy.
    documents = {"terms": capture("terms", "Пользовательское соглашение Noki")}
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for slug, text in documents.items():
        (args.output_dir / f"{slug}.txt").write_text(text, encoding="utf-8-sig")
    print("Current public user agreement captured: /legal/terms")


if __name__ == "__main__":
    main()
