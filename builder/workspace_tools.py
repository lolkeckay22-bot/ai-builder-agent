"""Bounded file operations for a single isolated agent workspace."""
import base64
import hashlib
import re
import shutil
import zipfile
from pathlib import Path

class WorkspaceTools:
    def __init__(self, root, event=lambda *args, **kwargs: None):
        self.root=Path(root).resolve()
        self.root.mkdir(parents=True,exist_ok=True)
        self.event=event
        self.changed=False

    def path(self, name):
        value=str(name)
        if not value or "\\" in value or Path(value).is_absolute() or re.match(r"^[A-Za-z]:",value) or ".." in Path(value).parts:
            raise ValueError(f"Unsafe path: {value}")
        target=(self.root/value).resolve()
        if target!=self.root and self.root not in target.parents: raise ValueError(f"Path escapes workspace: {value}")
        return target

    def execute(self, name, args):
        args=args if isinstance(args,dict) else {}
        path=self.path(args.get("path",".")) if name in {"list","read","write","create","edit","delete","search","copy","move","extract_archive"} else None
        if path==self.root and name in {"write","create","edit","delete","copy","move","extract_archive"}: raise ValueError("Cannot mutate workspace root")
        if name=="list":
            if not path.is_dir(): raise ValueError("Not a directory")
            return [{"path":p.relative_to(self.root).as_posix(),"type":"directory" if p.is_dir() else "file","size":p.stat().st_size if p.is_file() else 0} for p in sorted(path.iterdir())[:200]]
        if name=="read":
            if not path.is_file(): raise ValueError("File not found")
            data=path.read_bytes()
            if len(data)>120000: raise ValueError("File exceeds read limit")
            self.event("file.read",path=args["path"],label=f"Прочитан {args['path']}",icon="file")
            try: return {"text":data.decode("utf-8"),"sha256":hashlib.sha256(data).hexdigest()}
            except UnicodeDecodeError: return {"base64":base64.b64encode(data).decode(),"sha256":hashlib.sha256(data).hexdigest()}
        if name=="search":
            query=str(args.get("query", ""))
            if not query or len(query)>300: raise ValueError("Invalid search query")
            files=[path] if path.is_file() else (p for p in path.rglob("*") if p.is_file())
            matches=[]
            for file in files:
                if file.stat().st_size>200000: continue
                data=file.read_text(errors="ignore")
                for line_number,line in enumerate(data.splitlines(),1):
                    if query.casefold() in line.casefold(): matches.append({"path":file.relative_to(self.root).as_posix(),"line":line_number,"text":line[:500]})
                    if len(matches)>=100: return matches
            return matches
        if name in {"write","create"}:
            if name=="create" and path.exists(): raise ValueError("File already exists")
            content=str(args.get("content", ""))
            if len(content.encode())>500000: raise ValueError("Write exceeds limit")
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(content,encoding="utf-8")
            self.changed=True
            self.event("file.write",path=args["path"],label=f"{'Создан' if name=='create' else 'Записан'} {args['path']}",icon="file")
            return {"ok":True,"size":path.stat().st_size}
        if name=="edit":
            if not path.is_file() or path.stat().st_size>500000: raise ValueError("Text file unavailable")
            old=str(args.get("old", ""));new=str(args.get("new", ""))
            if not old or len(new)>500000: raise ValueError("Invalid edit")
            text=path.read_text(encoding="utf-8")
            count=text.count(old)
            if count!=1: raise ValueError(f"Expected one exact match, found {count}")
            path.write_text(text.replace(old,new,1),encoding="utf-8")
            self.changed=True
            self.event("file.write",path=args["path"],label=f"Изменён {args['path']}",icon="edit")
            return {"ok":True,"replacements":1}
        if name=="delete":
            if path.is_dir(): shutil.rmtree(path)
            elif path.is_file(): path.unlink()
            else: raise ValueError("Path not found")
            self.changed=True
            return {"ok":True}
        if name in {"copy","move"}:
            target=self.path(args.get("destination", ""))
            if not path.exists() or target.exists(): raise ValueError("Source missing or destination exists")
            target.parent.mkdir(parents=True,exist_ok=True)
            if name=="move": shutil.move(str(path),str(target))
            elif path.is_dir(): shutil.copytree(path,target)
            else: shutil.copy2(path,target)
            self.changed=True
            self.event("file.write",path=args["destination"],label=f"{name}: {args['path']} → {args['destination']}",icon="file")
            return {"ok":True}
        if name=="extract_archive":
            target=self.path(args.get("destination", ""))
            if not path.is_file() or target.exists(): raise ValueError("Archive missing or destination exists")
            with zipfile.ZipFile(path) as archive:
                infos=archive.infolist()
                if len(infos)>2000 or sum(info.file_size for info in infos)>200*1024*1024: raise ValueError("Archive exceeds limits")
                for info in infos:
                    member=self.path(f"{args['destination']}/{info.filename}")
                    if info.is_dir(): member.mkdir(parents=True,exist_ok=True)
                    else:
                        member.parent.mkdir(parents=True,exist_ok=True)
                        with archive.open(info) as src,member.open("wb") as dst: shutil.copyfileobj(src,dst)
            self.changed=True
            return {"ok":True,"entries":len(infos)}
        if name=="create_archive":
            source=self.path(args.get("source", ""))
            target=self.path(args.get("destination", ""))
            if not source.is_dir() or target.exists() or target.is_relative_to(source): raise ValueError("Invalid archive target")
            files=[p for p in source.rglob("*") if p.is_file()]
            if len(files)>2000 or sum(p.stat().st_size for p in files)>200*1024*1024: raise ValueError("Archive exceeds limits")
            target.parent.mkdir(parents=True,exist_ok=True)
            with zipfile.ZipFile(target,"w",zipfile.ZIP_DEFLATED) as archive:
                for file in files: archive.write(file,file.relative_to(source))
            with zipfile.ZipFile(target) as archive:
                if archive.testzip(): raise ValueError("Archive failed integrity check")
            self.changed=True
            return {"ok":True,"entries":len(files),"sha256":hashlib.sha256(target.read_bytes()).hexdigest()}
        raise ValueError(f"Unknown tool: {name}")
