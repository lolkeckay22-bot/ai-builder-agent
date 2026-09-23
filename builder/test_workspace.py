import importlib.util
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0,str(Path(__file__).parent))
spec=importlib.util.spec_from_file_location('agent_builder',Path(__file__).with_name('generate.py'))
agent=importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)

class WorkspaceTests(unittest.TestCase):
    def test_archive_edit_recovers_from_tool_failure_and_verifies_title(self):
        old=os.getcwd();old_prompt=os.environ.get('PROMPT')
        try:
            with tempfile.TemporaryDirectory() as tmp:
                os.chdir(tmp)
                Path('input').mkdir()
                with zipfile.ZipFile('input/theme.mtz','w') as archive:
                    archive.writestr('description.xml','<MIUI-Theme><title>Old</title></MIUI-Theme>')
                    archive.writestr('theme_values.xml','<MIUI_Theme_Values/>')
                decisions=iter([
                    {'tool':'read','args':{'path':'description.xml'}},
                    {'tool':'edit','args':{'path':'description.xml','old':'Missing','new':'Test Theme'}},
                    {'tool':'edit','args':{'path':'description.xml','old':'Old','new':'Test Theme'}},
                    {'done':True},
                ])
                agent.call_ai=lambda *a,**k:json.dumps(next(decisions))
                agent.make_archive('замени название темы на Test Theme','test','mtz')
                os.environ['PROMPT']='замени название темы на Test Theme'
                agent.verify_artifact('mtz')
                with zipfile.ZipFile('output/WorkAI-test.mtz') as archive:
                    self.assertIn(b'Test Theme',archive.read('description.xml'))
                    self.assertEqual(archive.testzip(),None)
                os.environ['PROMPT']='замени название темы на Another Theme'
                with self.assertRaisesRegex(ValueError,'title mismatch'):
                    agent.verify_artifact('mtz')
        finally:
            os.chdir(old)
            if old_prompt is None: os.environ.pop('PROMPT',None)
            else: os.environ['PROMPT']=old_prompt

    def test_traversal_is_rejected_before_extraction(self):
        old=os.getcwd()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                os.chdir(tmp);Path('input').mkdir()
                with zipfile.ZipFile('input/theme.zip','w') as archive:
                    archive.writestr('../escape.txt','unsafe')
                with self.assertRaisesRegex(ValueError,'Unsafe archive path'):
                    agent.make_archive('edit archive','test','zip')
                self.assertFalse(Path('escape.txt').exists())
        finally: os.chdir(old)

if __name__=='__main__': unittest.main()
