"""Legal capture tests; run without contacting the production website."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_installer", Path(__file__).with_name("prepare-installer.py"))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class LegalCaptureTest(unittest.TestCase):
    def test_full_document_includes_closed_sections_and_table_cells(self):
        document = installer.LegalDocument()
        document.feed('''<header><h1>Документ Noki</h1><div class="legal-hero__meta">Редакция 1</div></header>
            <nav>Navigation is not a legal term</nav>
            <article class="legal-document"><button>Развернуть</button>
              <details><summary><h2>Закрытый раздел</h2></summary>
                <p>Данные &amp; цели</p><table><tr><td>Аккаунт</td><td>Вход</td></tr></table>
                <a href="/legal/terms">Условия</a></details>
            </article><script>Private bootstrap data</script>''')
        text = document.text()
        for expected in ("Документ Noki", "Редакция 1", "Закрытый раздел", "Данные & цели", "Аккаунт | Вход", "https://noki.ykino.tech/legal/terms"):
            self.assertIn(expected, text)
        for excluded in ("Navigation", "Развернуть", "Private bootstrap"):
            self.assertNotIn(excluded, text)
        self.assertEqual(1, document.sections)

    def test_network_failure_is_fatal_instead_of_using_old_files(self):
        with patch.object(installer, "urlopen", side_effect=OSError("offline")):
            with self.assertRaisesRegex(OSError, "offline"):
                installer.capture("terms", "Пользовательское соглашение Noki")


if __name__ == "__main__":
    unittest.main()
