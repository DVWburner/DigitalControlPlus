Digital Control+ @VERSION@ (Standalone NEMESIS Package)
Package ID: gen5w.digitalcontrol

No HMG-API required. Works on any NEMESIS-equipped Hyundai/Kia/Genesis Gen5W head unit.

USB STICK INSTALLATION:
1. Format a USB flash drive as FAT32.
2. Extract the contents of gen5w-digital-control-@VERSION@-nemesis.zip directly
   to the root of the USB drive so it contains:
     - NEMESIS.data
     - packages/gen5w.digitalcontrol.zip
3. Plug the USB drive into the car's data USB port while the head unit is booted.
4. Confirm the NEMESIS prompt to install (or update) Digital Control+.
5. Open Digital Control+ from NEMESIS Manager -> My Packages (or your launcher)
   and set your 4-8 digit owner Security PIN.

UNINSTALL:
Remove "Digital Control+" in NEMESIS Manager. Removing the package automatically
runs pm uninstall to wipe the car's cryptographic identity, PIN, and trusted phones.
