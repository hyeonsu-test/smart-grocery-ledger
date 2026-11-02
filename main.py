"""Launch with Python 3.10+; --demo uses an isolated fictional data file."""
import argparse
from pathlib import Path
from grocery.ui import LedgerApp

if __name__=='__main__':
    parser=argparse.ArgumentParser(description='Smart Grocery Ledger beta')
    parser.add_argument('--demo',action='store_true',help='Use fictional demonstration data')
    parser.add_argument('--data-dir',type=Path,default=Path(__file__).parent/'userdata')
    args=parser.parse_args()
    LedgerApp(args.data_dir,args.demo).mainloop()
