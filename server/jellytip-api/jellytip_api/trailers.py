"""Trailer cleanup. Trailers live in `<title folder>/trailers/`; a title's trailers folder is removed
only when the title folder no longer holds a non-trailer video file. Nothing but `trailers/` is ever deleted.

    python3 -m jellytip_api.trailers sweep [--apply]    # dry-run unless --apply
"""
import os
import shutil
import sys
import time

VIDEO_EXT = {".mkv", ".mp4", ".m4v", ".avi", ".mov", ".ts", ".m2ts", ".wmv", ".mpg", ".mpeg", ".webm", ".iso"}
MAX_REMOVE_FRACTION = 0.25  # more than this share of all trailer folders (and >20) looks like a missing mount, not deletions


def default_roots():
    return [os.path.expanduser("~/media/Movies"), os.path.expanduser("~/media/TV Shows")]


def has_media(folder):
    """True if any non-trailer video file exists under folder (recursive, skipping `trailers/`)."""
    for cur, dirs, files in os.walk(folder):
        dirs[:] = [d for d in dirs if not (d == "trailers" and cur == folder)]
        if any(os.path.splitext(f)[1].lower() in VIDEO_EXT for f in files):
            return True
    return False


def dir_size(path):
    return sum(os.path.getsize(os.path.join(c, f)) for c, _, fs in os.walk(path) for f in fs if os.path.isfile(os.path.join(c, f)))


def orphans(roots):
    """Returns (all_trailer_dirs, orphan_dirs). Raises RuntimeError if a root looks unmounted."""
    allt, orph = [], []
    for root in roots:
        if not os.path.isdir(root) or not os.listdir(root):
            raise RuntimeError(f"media root missing or empty: {root}")
        for name in sorted(os.listdir(root)):
            folder = os.path.join(root, name)
            t = os.path.join(folder, "trailers")
            if os.path.isdir(folder) and os.path.isdir(t) and not os.path.islink(t):
                allt.append(t)
                if not has_media(folder):
                    orph.append(t)
    return allt, orph


def remove(trailers_dir, apply, log):
    size = dir_size(trailers_dir)
    log(f"{'REMOVE' if apply else 'WOULD REMOVE'} {trailers_dir} ({size / 1e6:.1f} MB)")
    if apply:
        if os.path.basename(trailers_dir) != "trailers" or os.path.islink(trailers_dir):
            raise RuntimeError("refusing: not a plain trailers dir")
        shutil.rmtree(trailers_dir)
    return size


def sweep(roots=None, apply=False, force=False, log=print):
    allt, orph = orphans(roots or default_roots())
    if apply and not force and len(orph) > 20 and len(orph) > MAX_REMOVE_FRACTION * len(allt):
        raise RuntimeError(f"abort: {len(orph)} of {len(allt)} trailer folders look orphaned; rerun with --force if real")
    total = sum(remove(t, apply, log) for t in orph)
    log(f"{'removed' if apply else 'would remove'} {len(orph)} of {len(allt)} trailer folders, {total / 1e6:.1f} MB")
    return {"total": len(allt), "orphans": orph, "bytes": total, "applied": apply}


def remove_for_folder(folder, log=print):
    """Immediate cleanup after a delete: remove folder/trailers if the title no longer has media. Returns the reason string."""
    t = os.path.join(folder, "trailers")
    if not os.path.isdir(t) or os.path.islink(t):
        return "no trailers"
    if os.path.isdir(folder) and has_media(folder):
        return "title still has media"
    remove(t, True, log)
    return "removed"


def main(argv):
    if not argv or argv[0] != "sweep":
        print(__doc__)
        return 2
    try:
        sweep(apply="--apply" in argv, force="--force" in argv,
              log=lambda m: print(time.strftime("%F %T"), m, flush=True))
    except RuntimeError as e:
        print(time.strftime("%F %T"), "ERROR", e, flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
