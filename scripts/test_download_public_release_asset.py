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


class PreparedGradleTests(unittest.TestCase):
    def test_ci_launcher_uses_pinned_install_and_preserves_argument_boundaries(self):
        import os
        prepare=Path(__file__).with_name("ci_prepare_gradle.sh").resolve()
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            tools=root/"tools with spaces"
            tools.mkdir()
            binary=tools/"gradle"
            binary.write_text("""#!/usr/bin/env bash
if [ "$1" = --version ]; then printf '\\nGradle %s\\n' "$VERSION"; exit 0; fi
printf '%s\\n' "$@" > "$CALLS"
exit 42
""")
            binary.chmod(0o755)
            env=dict(os.environ,CI="true",VERSION="9.3.1",CALLS=str(root/"calls"),
                     PATH=str(tools)+os.pathsep+os.environ["PATH"])
            result=subprocess.run(["bash",str(prepare)],cwd=root,env=env,capture_output=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(["./gradlew","task with spaces","-Pkey=value"],cwd=root,env=env,capture_output=True)
            self.assertEqual(result.returncode,42)
            self.assertEqual((root/"calls").read_text().splitlines(),["task with spaces","-Pkey=value"])
            launcher=(root/"gradlew").read_bytes()
            result=subprocess.run(["bash",str(prepare)],cwd=root,env=dict(env,VERSION="8.10"),capture_output=True)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual((root/"gradlew").read_bytes(),launcher)
            (root/"gradlew").unlink()
            for overrides in [dict(VERSION="8.10"),dict(CI="false")]:
                result=subprocess.run(["bash",str(prepare)],cwd=root,env=dict(env,**overrides),capture_output=True)
                self.assertNotEqual(result.returncode,0)
                self.assertFalse((root/"gradlew").exists())
    def test_all_jobs_keep_the_same_pinned_gradle_and_ci_launcher(self):
        workflow=(Path(__file__).resolve().parent.parent/".github/workflows/android.yml").read_text()
        self.assertEqual(workflow.count("gradle-version: '9.3.1'"),3)
        self.assertEqual(workflow.count("scripts/ci_prepare_gradle.sh"),4)
        self.assertNotIn("--no-validate-url",workflow)
        self.assertNotIn("gradle wrapper --",workflow)

if __name__=="__main__":
    unittest.main()
