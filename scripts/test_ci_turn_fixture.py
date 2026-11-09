"""Fail closed when a fixture or its credentials are absent; never contact a real service."""
import os,pathlib,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parent.parent
class TurnFixtureContract(unittest.TestCase):
    def test_fixture_refuses_non_ci_before_starting_any_server(self):
        with tempfile.TemporaryDirectory() as temp:
            result=subprocess.run(['bash',str(ROOT/'scripts/ci_turn_fixture.sh'),'start'],
                env=dict(os.environ,CI='false',RUNNER_TEMP=temp),capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(list(pathlib.Path(temp).iterdir()),[])
    def test_connected_runner_refuses_missing_fixture_without_running_gradle(self):
        with tempfile.TemporaryDirectory() as temp:
            launcher=pathlib.Path(temp)/'gradlew'
            launcher.write_text('#!/bin/sh\ntouch should-not-run\n');launcher.chmod(0o755)
            env=dict(os.environ,CI='true',LANU_TURN_TEST_USER='',LANU_TURN_TEST_PASSWORD='')
            result=subprocess.run(['bash',str(ROOT/'scripts/ci_connected_runtime.sh'),'debug'],cwd=temp,env=env,capture_output=True)
            self.assertNotEqual(result.returncode,0)
            self.assertFalse((pathlib.Path(temp)/'should-not-run').exists())
    def test_both_native_variants_require_relay_fixture_and_always_retire_it(self):
        workflow=(ROOT/'.github/workflows/android.yml').read_text()
        release=(ROOT/'scripts/ci_release_runtime.sh').read_text()
        fixture=(ROOT/'scripts/ci_turn_fixture.sh').read_text()
        native=(ROOT/'app/src/androidTest/java/com/example/TurnRelayNativeAcceptanceTest.kt').read_text()
        self.assertIn('bash scripts/ci_connected_runtime.sh debug',workflow)
        self.assertIn('bash scripts/ci_connected_runtime.sh release',release)
        self.assertIn('if: always()\n        run: bash scripts/ci_turn_fixture.sh stop',workflow)
        for marker in ('listening-ip=127.0.0.1','relay-ip=127.0.0.1','external-ip=10.0.2.2/127.0.0.1','lt-cred-mech','no-cli','no-tls','no-dtls','max-allocate-lifetime=120'):
            self.assertIn(marker,fixture)
        self.assertNotIn('no-auth',fixture)
        self.assertNotIn('assumeTrue',native)
        self.assertIn('IceTransportsType.RELAY',native)
        self.assertIn('receivedByA.await()',native); self.assertIn('receivedByB.await()',native)
if __name__=='__main__': unittest.main()
