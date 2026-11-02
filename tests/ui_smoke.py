"""Construct all screens in an isolated process; this is not screenshot QA."""
import tempfile
from pathlib import Path
from grocery.ui import LedgerApp

root=Path(__file__).resolve().parent.parent/'userdata'/'test-temp';root.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(dir=root) as directory:
    app=LedgerApp(directory,True);app.withdraw()
    try:
        for index in range(5):
            app.active=index;app.render();app.update_idletasks()
            print('screen',index+1,'constructed')
        app.active=3;app.render();app.history();assert len(app.price_tree.get_children())==9
        app.active=4;app.render();assert len(app.target_tree.get_children())==3
        app.show_api();app.update_idletasks()
        print('history, favorite targets, API manual constructed')
    finally:app.destroy()
