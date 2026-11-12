import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('deploy', pathlib.Path(__file__).parents[1] / 'deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class MusicReleaseTest(unittest.TestCase):
    def test_old_release_cannot_remove_radio(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/com/leon/workflow/controller/MusicController.class', b'/audio')
            with self.assertRaisesRegex(RuntimeError, 'radio endpoint'):
                deploy.verify_music_routes(jar)

    def test_radio_only_release_cannot_remove_playlists(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/com/leon/workflow/controller/MusicController.class', b'/audio /radio')
            with self.assertRaisesRegex(RuntimeError, 'online playlists'):
                deploy.verify_music_routes(jar)

    def test_playlist_release_cannot_remove_saved_ordering(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/com/leon/workflow/controller/MusicController.class', b'/audio /radio')
                archive.writestr('BOOT-INF/classes/com/leon/workflow/controller/MusicPlaylistController.class', b'/api/music/playlists')
            with self.assertRaisesRegex(RuntimeError, 'playlist ordering'):
                deploy.verify_music_routes(jar)

    def test_radio_release_passes_guard(self):
        with tempfile.TemporaryDirectory() as folder:
            jar = pathlib.Path(folder) / 'app.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('BOOT-INF/classes/com/leon/workflow/controller/MusicController.class', b'/audio /radio')
                archive.writestr('BOOT-INF/classes/com/leon/workflow/controller/MusicPlaylistController.class', b'/api/music/playlists /{id}/order')
            deploy.verify_music_routes(jar)


if __name__ == '__main__':
    unittest.main()
