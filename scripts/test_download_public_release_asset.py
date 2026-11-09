#!/usr/bin/env python3
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("download", Path(__file__).with_name("download_public_release_asset.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
REPO, TAG, NAME = "kanunal99-jpg/LANU-Hesap-Makinesi", "v1.2.0-signed.1180", "app-debug.apk.sha256"

class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dest = Path(self.tmp.name) / NAME
        self.dest.write_bytes(b"previous")
        self.calls = []
    def runner(self, responses):
        def run(args, **kwargs):
            self.calls.append(args)
            code, body = responses.pop(0)
            Path(args[args.index("--output")+1]).write_bytes(body)
            return SimpleNamespace(returncode=code)
        return run
    def metadata(self, **changes):
        value = dict(tag_name=TAG, draft=False, assets=[dict(id=42, name=NAME, state="uploaded")])
        value.update(changes)
        return json.dumps(value).encode()
    def test_public_success_uses_no_api(self):
        self.assertEqual(module.download(REPO,TAG,NAME,self.dest,self.runner([(0,b"checksum")])), "public URL")
        self.assertEqual(len(self.calls),1)
        self.assertEqual(self.dest.read_bytes(),b"checksum")
    def test_504_partial_public_download_uses_exact_anonymous_api(self):
        route=module.download(REPO,TAG,NAME,self.dest,self.runner([(22,b"partial"),(0,self.metadata()),(0,b"verified")]))
        self.assertEqual(route,"anonymous public API")
        self.assertEqual(self.dest.read_bytes(),b"verified")
        self.assertEqual(self.calls[-1][-1],f"https://api.github.com/repos/{REPO}/releases/assets/42")
        self.assertNotIn("Authorization",repr(self.calls))
        self.assertIn("--max-filesize",self.calls[-1])
    def test_both_paths_fail_preserves_file_and_cleans_partials(self):
        with self.assertRaises(ValueError):
            module.download(REPO,TAG,NAME,self.dest,self.runner([(22,b"partial"),(0,self.metadata()),(22,b"truncated")]))
        self.assertEqual(self.dest.read_bytes(),b"previous")
        self.assertEqual(list(Path(self.tmp.name).iterdir()),[self.dest])
    def test_bad_metadata_fails_closed_before_binary_request(self):
        invalid=[self.metadata(tag_name="other"),self.metadata(draft=True),
                 self.metadata(assets=[]), self.metadata(assets=[dict(id=True,name=NAME,state="uploaded")]),
                 self.metadata(assets=[dict(id=42,name=NAME,state="new")]),
                 self.metadata(assets=[dict(id=42,name=NAME,state="uploaded")]*2),
                 b"not-json", b"[]"]
        for body in invalid:
            with self.subTest(body=body), self.assertRaises((ValueError,TypeError)):
                module.download(REPO,TAG,NAME,self.dest,self.runner([(22,b"partial"),(0,body)]))
            self.assertEqual(self.dest.read_bytes(),b"previous")
    def test_paths_are_validated_before_any_network(self):
        for repo,tag,name in [("../x",TAG,NAME),(REPO,"../../tag",NAME),(REPO,TAG,"../key")]:
            with self.assertRaises(ValueError):
                module.download(repo,tag,name,self.dest,self.runner([]))
        self.assertEqual(self.calls,[])
    def test_timeout_and_oversized_success_do_not_replace_file(self):
        def timeout(args,**kwargs):
            raise subprocess.TimeoutExpired(args,1)
        with self.assertRaises(ValueError):
            module.download(REPO,TAG,NAME,self.dest,timeout)
        oversized=b"x"*4097
        with self.assertRaises(ValueError):
            module.download(REPO,TAG,NAME,self.dest,self.runner([(0,oversized),(0,self.metadata()),(0,oversized)]))
        self.assertEqual(self.dest.read_bytes(),b"previous")


class WrapperFallbackTests(unittest.TestCase):
    def test_real_workflow_commands_use_bounded_official_host_fallback(self):
        import os
        workflow=(Path(__file__).resolve().parent.parent/".github/workflows/android.yml").read_text()
        import re
        commands=re.findall(r"for wrapper_host in services.gradle.org downloads.gradle.org;.*?done; done",workflow)
        self.assertEqual(len(commands),4)
        self.assertEqual(len(set(commands)),1)
        command=commands[0]
        self.assertNotIn("--no-validate-url",command)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            log=root/"calls"
            gradle=root/"gradle"
            gradle.write_text("""#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$LOG"
if [ "$MODE" = "primary" ]; then exit 0; fi
if [ "$MODE" = "fallback" ] && [[ "$*" == *https://downloads.gradle.org/* ]]; then exit 0; fi
exit 1
""")
            sleep=root/"sleep"
            sleep.write_text(chr(10).join(["#!/usr/bin/env bash", "exit 0", ""]))
            for file in (gradle,sleep):
                file.chmod(0o755)
            for mode,count,ok in [("primary",1,True),("fallback",3,True),("failed",4,False)]:
                with self.subTest(mode=mode):
                    log.write_text("")
                    env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ["PATH"],LOG=str(log),MODE=mode)
                    result=subprocess.run(["bash","-c",command],env=env,capture_output=True,timeout=20)
                    self.assertEqual(result.returncode==0,ok)
                    lines=log.read_text().splitlines()
                    self.assertEqual(len(lines),count)
                    self.assertTrue(all("--gradle-version 9.3.1" in line for line in lines))
                    self.assertTrue(all("--gradle-distribution-url https://" in line for line in lines))

if __name__=="__main__":
    unittest.main()
