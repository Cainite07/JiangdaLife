"""Offline failure-injection tests for the dual-host release sequence."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import publish_release as release


class Interrupted(Exception):
    pass


class ReleaseResumeTest(unittest.TestCase):
    def test_retry_after_each_committed_remote_operation(self):
        manifest = json.loads((release.ROOT / "version.json").read_text(encoding="utf-8"))
        version = manifest["versionName"]
        tag = "v" + version
        head = "fixture-commit"
        apk = "JiangdaLife-" + version + ".apk"
        with tempfile.TemporaryDirectory() as tmp, contextlib.chdir(tmp):
            root = Path(tmp)
            target = root / "app/build/outputs/distribution" / apk
            target.parent.mkdir(parents=True)
            target.write_bytes(b"fixture APK")
            (root / "version.json").write_text(json.dumps(manifest), encoding="utf-8")
            (root / "app/build.gradle.kts").write_text(
                f'versionName = "{version}"\nversionCode = {manifest["versionCode"]}')
            for checkpoint in range(1, 13):
                with self.subTest(checkpoint=checkpoint):
                    tags, mains, releases, assets = {}, {}, {}, {}
                    local_tag = False
                    operations = 0

                    def committed():
                        nonlocal operations
                        operations += 1
                        if operations == checkpoint:
                            raise Interrupted()

                    def git(*args):
                        nonlocal local_tag
                        cmd, *rest = args
                        if cmd == "ls-remote":
                            remote, ref = rest[:2]
                            oid = tags.get(remote) if ref.startswith("refs/tags/") else mains.get(remote)
                            return f"{oid} {ref}" if oid else ""
                        if cmd == "push":
                            remote, ref = rest
                            if ref.startswith("refs/tags/"):
                                tags[remote] = head
                            else:
                                assert all(not r["prerelease"] for r in releases.values())
                                mains[remote] = head
                            committed()
                        if cmd == "tag":
                            if "--list" in rest:
                                return tag if local_tag else ""
                            local_tag = True
                        if cmd in ("rev-parse", "rev-list"):
                            return head
                        return ""

                    class FakeApi(release.Api):
                        def request(self, path, method="GET", values=None, payload=None, content_type=None):
                            host = self.platform
                            if path.startswith("/releases?"):
                                return [releases[host]] if host in releases else []
                            if method == "POST" and path == "/releases":
                                assert host not in releases
                                releases[host] = dict(id=1, tag_name=tag, prerelease=True,
                                                      upload_url="https://fixture/upload{?name}")
                                committed()
                                return releases[host]
                            if method == "PATCH":
                                releases[host].update(name=values["name"], draft=False, prerelease=False)
                                committed()
                                return releases[host]
                            if path == "/releases/1":
                                return releases[host]
                            if method == "GET":
                                return [a for (h, _), a in assets.items() if h == host]
                            name = apk if (host, apk) not in assets else "SHA256SUMS.txt"
                            data = (target.parent / name).read_bytes()
                            assert data in payload
                            assert (host, name) not in assets
                            assets[host, name] = dict(name=name, size=len(data), state="uploaded",
                                digest="sha256:" + hashlib.sha256(data).hexdigest())
                            committed()
                            return assets[host, name]

                    def verify(url, path, direct=False):
                        host = "gitee" if direct else "github"
                        assert assets[host, path.name]["digest"] == "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()

                    with patch.multiple(release, ROOT=root, Api=FakeApi, git=git,
                            verify_repository=lambda *_: None, verify_apk=lambda *_: None,
                            credential=lambda *_: "fixture", verify_download=verify), contextlib.redirect_stdout(io.StringIO()):
                        with self.assertRaises(Interrupted):
                            release.main()
                        release.main()
                    self.assertEqual(set(mains), {"origin", "gitee"})
                    self.assertEqual(len(assets), 4)

    def test_same_size_or_incomplete_github_upload_cannot_be_reused(self):
        api = release.Api("github", "fixture")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "test.apk"
            path.write_bytes(b"abc")
            for state, digest in [("starter", None), ("uploaded", None), ("uploaded", "sha256:wrong")]:
                asset = dict(name=path.name, size=3, state=state, digest=digest)
                with patch.object(api, "request", return_value=[asset]), self.assertRaises(ValueError):
                    api.upload({"id": 1}, path)
