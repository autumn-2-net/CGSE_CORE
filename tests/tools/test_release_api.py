import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('abi', Path(__file__).resolve().parents[2] / 'tools/release/check_abi.py')
abi = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abi)


class ReleaseApiTest(unittest.TestCase):
    def test_preserves_symbols_and_allows_concrete_additions(self):
        old = {'T X 1 java.lang.Object ', 'M X f(long):long 1'}
        self.assertFalse(abi.compare(old, old | {'M X extra():int 1', 'T Y 1 java.lang.Object ', 'M Y f():int 1025'}))

    def test_rejects_removed_changed_or_new_abstract_contracts(self):
        old = {'T X 1 java.lang.Object ', 'M X f(long):long 1'}
        for current in [old - {'M X f(long):long 1'},
                        {'T X 17 java.lang.Object ', 'M X f(long):long 1'},
                        old | {'M X extra():int 1025'}]:
            self.assertTrue(abi.compare(old, current))


if __name__ == '__main__':
    unittest.main()
