import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent, ReactNode } from "react";

import { api } from "../api/client";
import type { AgentHandle, Change, FileContent, FileEntry, GitCommit, GitFileStatus, GitStatus, Project, RecoveryItem, Task, TaskMessage } from "../api/types";
import { openCommandPalette, setIdeIntentHandler, type IdeIntent } from "../app/commands";
import { parseUnifiedDiff } from "../app/diff";
import { openTerminalWindow } from "../app/terminalWindow";
import { toast } from "../app/toast";
import { MOBILE_QUERY, useMediaQuery } from "../app/useMediaQuery";
import { clockOf } from "../components/solo/SoloChat";
import { ROLE_LABELS, STATE_LABELS } from "../data/team";

const MAX_DEPTH = 4;
const LS_COMMIT_TOUCHED = "flux.ide.commitTouched";
type RailPanel = "files" | "search" | "git" | "debug" | "ext";
type BottomTab = "flow" | "changes" | "git";
type MobilePanel = "none" | "files" | "agent";
const CHANGES_TAB = "changes";

function errorMessage(error: unknown): string { return error instanceof Error ? error.message : String(error); }
function iconClass(path: string): string { const name = path.split("/").pop() ?? path; if (name.startsWith(".")) return "git"; if (/\.(tsx?|jsx?|mjs|cjs)$/.test(name)) return "ts"; if (/\.(json|jsonc)$/.test(name)) return "json"; if (/\.(md|markdown)$/.test(name)) return "md"; return ""; }
function langLabel(path: string): string { const name = path.split("/").pop() ?? path; const table: [RegExp, string][] = [[/\.tsx$/, "TypeScript JSX"],[/\.ts$/, "TypeScript"],[/\.jsx$/, "JavaScript JSX"],[/\.(js|mjs|cjs)$/, "JavaScript"],[/\.json$/, "JSON"],[/\.md$/, "Markdown"],[/\.py$/, "Python"],[/\.css$/, "CSS"],[/\.html$/, "HTML"],[/\.(yml|yaml)$/, "YAML"],[/\.toml$/, "TOML"],[/\.sh$/, "Shell Script"]]; for (const [pattern,label] of table) if(pattern.test(name)) return label; return "纯文本"; }
function lexerKind(path: string): "md" | "py" | "code" { const name=path.split("/").pop()??path; if(name.endsWith(".md")) return "md"; if(name.endsWith(".py")) return "py"; return "code"; }
const TOKEN_RE=/(\/\/[^\n]*|#[^\n]*)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*"|`[^`]*`)|\b(import|from|export|function|const|let|var|async|await|return|try|catch|finally|if|else|new|default|void|type|interface|class|extends|for|while|def|self|None|True|False|raise|with|yield|lambda|pass|elif|except|assert|global|in|not|and|or)\b|\b(\d+(?:\.\d+)?)\b|(<\/?)([A-Z][A-Za-z0-9_.]*)/g;
interface Token{text:string;cls?:string}
function highlight(line:string,kind:"md"|"py"|"code"):Token[]{if(kind==="md"&&/^#{1,6}\s/.test(line.trimStart()))return[{text:line,cls:"t"}];const tokens:Token[]=[];let last=0;TOKEN_RE.lastIndex=0;let match=TOKEN_RE.exec(line);while(match!==null){if(match.index>last)tokens.push({text:line.slice(last,match.index)});if(match[1])tokens.push({text:match[1],cls:"c"});else if(match[2])tokens.push({text:match[2],cls:"s"});else if(match[3])tokens.push({text:match[3],cls:"k"});else if(match[4])tokens.push({text:match[4],cls:"n"});else if(match[6]){tokens.push({text:match[5]??""});tokens.push({text:match[6],cls:"comp"});}last=match.index+match[0].length;match=TOKEN_RE.exec(line);}if(last<line.length)tokens.push({text:line.slice(last)});return tokens.length>0?tokens:[{text:line}];}
interface TreeNode{name:string;path:string;kind:"dir"|"file";children:TreeNode[]}
function buildTree(entries:FileEntry[]):TreeNode[]{const root:TreeNode[]=[];const index=new Map<string,TreeNode>();for(const entry of[...entries].sort((a,b)=>a.path.localeCompare(b.path))){const parts=entry.path.split("/");let level=root;let prefix="";parts.forEach((part,i)=>{prefix=prefix?`${prefix}/${part}`:part;const isLeaf=i===parts.length-1;let node=index.get(prefix);if(!node){node={name:part,path:prefix,kind:isLeaf?entry.kind:"dir",children:[]};index.set(prefix,node);level.push(node);}level=node.children;});}const sortLevel=(nodes:TreeNode[]):TreeNode[]=>{nodes.sort((a,b)=>a.kind===b.kind?a.name.localeCompare(b.name):a.kind==="dir"?-1:1);nodes.forEach(node=>sortLevel(node.children));return nodes};return sortLevel(root);}
function mergeEntries(current:FileEntry[],incoming:FileEntry[]):FileEntry[]{const seen=new Set(current.map(entry=>entry.path));return[...current,...incoming.filter(entry=>!seen.has(entry.path))];}
function gitLetter(file:GitFileStatus):string{if(file.untracked)return"U";const worktree=file.worktree_status.trim();const index=file.index_status.trim();return worktree||index||"M";}
function changeBadge(change:Change):{label:string;cls:string}{if(change.status==="pending")return{label:"待审核",cls:"b-run"};if(change.status==="applied")return{label:"已落盘",cls:"b-ok"};if(change.status==="accepted")return{label:"已批准",cls:"b-ok"};if(change.status==="rolled_back")return{label:"已回滚",cls:""};if(change.status==="failed")return{label:"落盘失败",cls:""};return{label:"已拒绝",cls:""};}
function kindLetter(kind:Change["kind"]):string{if(kind==="create")return"A";if(kind==="delete")return"D";return"M";}
function kindLabel(kind:Change["kind"]):string{if(kind==="create")return"新建";if(kind==="delete")return"删除";return"修改";}
function failureSummary(log:string):string{const lines=log.split("\n").map(line=>line.trim()).filter(Boolean);const failed=lines.find(line=>/^FAILED\b/.test(line));const counts=lines.find(line=>/^\d+\s+(failed|passed|error)/i.test(line));const shortCounts=counts?.replace(/\s+in\s+[\d.]+s$/,"");if(shortCounts&&failed)return`${shortCounts} · ${failed}`;if(failed)return failed;if(shortCounts)return shortCounts;return lines[0]??"未知原因";}
function FailedBanner({log}:{log:string}){const[open,setOpen]=useState(false);return <div className="diff-banner is-failed"><div className="db-row"><span className="t-err">落盘失败：{failureSummary(log)}</span><button type="button" className="link" onClick={()=>setOpen(prev=>!prev)}>{open?"收起日志":"展开完整日志"}</button></div>{open?<pre className="apply-log">{log}</pre>:null}</div>;}

export function IdePage({onBackToSolo}:{onBackToSolo:()=>void}){
  const[projects,setProjects]=useState<Project[]>([]);const[projectId,setProjectId]=useState<string|null>(null);const[projectOpen,setProjectOpen]=useState(false);const[workspaceRoot,setWorkspaceRoot]=useState("");const[workspaceBusy,setWorkspaceBusy]=useState(false);
  const[railPanel,setRailPanel]=useState<RailPanel>("files");const[filesCollapsed,setFilesCollapsed]=useState(false);const[agentClosed,setAgentClosed]=useState(false);const[focusMode,setFocusMode]=useState(false);const isMobile=useMediaQuery(MOBILE_QUERY);const[mPanel,setMPanel]=useState<MobilePanel>("none");
  const[entries,setEntries]=useState<FileEntry[]>([]);const[treeRoot,setTreeRoot]=useState("");const[treeTruncated,setTreeTruncated]=useState(false);const[treeLoading,setTreeLoading]=useState(false);const[treeError,setTreeError]=useState<string|null>(null);const[expanded,setExpanded]=useState<Set<string>>(new Set());const[search,setSearch]=useState("");
  const[openTabs,setOpenTabs]=useState<string[]>([]);const[activeTab,setActiveTab]=useState<string|null>(CHANGES_TAB);const[files,setFiles]=useState<Record<string,FileContent>>({});const[fileBusy,setFileBusy]=useState(false);const[fileError,setFileError]=useState<string|null>(null);
  const[changes,setChanges]=useState<Change[]>([]);const[actionBusyId,setActionBusyId]=useState<string|null>(null);const[rollbackBusy,setRollbackBusy]=useState(false);const[gitStatus,setGitStatus]=useState<GitStatus|null>(null);const[gitError,setGitError]=useState<string|null>(null);const[commitMessage,setCommitMessage]=useState("");const[commitTouched,setCommitTouched]=useState(()=>window.localStorage.getItem(LS_COMMIT_TOUCHED)==="1");const[commitBusy,setCommitBusy]=useState(false);const[lastCommit,setLastCommit]=useState<GitCommit|null>(null);const[agents,setAgents]=useState<AgentHandle[]>([]);const[task,setTask]=useState<Task|null>(null);const[taskMessages,setTaskMessages]=useState<TaskMessage[]>([]);const[bottomTab,setBottomTab]=useState<BottomTab>("flow");const[bottomOpen,setBottomOpen]=useState(true);const[bottomHeight,setBottomHeight]=useState(200);const[reviewId,setReviewId]=useState<string|null>(null);const[reviewBusy,setReviewBusy]=useState(false);const[recoveryItems,setRecoveryItems]=useState<RecoveryItem[]>([]);const[recoveryOpenId,setRecoveryOpenId]=useState<string|null>(null);const[recoveryBusy,setRecoveryBusy]=useState(false);
  const desktop=typeof window!=="undefined"?window.fluxDesktop:null;
  const displayWorkspace=workspaceRoot||treeRoot||"未选择工作目录";

  const loadProjects=useCallback(async()=>{try{const list=await api.listProjects();setProjects(list);setProjectId(current=>current??list[0]?.id??null);}catch(error){toast(errorMessage(error),"error");}},[]);
  const loadWorkspace=useCallback(async()=>{try{const data=await api.getWorkspaceRoot();setWorkspaceRoot(data.root);}catch(error){toast(errorMessage(error),"error");}},[]);
  const loadTree=useCallback(async(id:string|null)=>{if(!id){setEntries([]);setTreeRoot("");return;}setTreeLoading(true);try{const page=await api.listFiles(id,{depth:MAX_DEPTH});setEntries(page.entries);setTreeRoot(page.root);setTreeTruncated(page.truncated);setTreeError(null);}catch(error){setEntries([]);setTreeError(errorMessage(error));}finally{setTreeLoading(false);}},[]);
  const loadChanges=useCallback(async()=>{try{setChanges(await api.listChanges());}catch(error){toast(errorMessage(error),"error");}},[]);
  const loadRecovery=useCallback(async()=>{try{setRecoveryItems(await api.listRecovery());}catch(error){toast(errorMessage(error),"error");}},[]);
  const loadGit=useCallback(async()=>{try{setGitStatus(await api.gitStatus());setGitError(null);}catch(error){setGitStatus(null);setGitError(errorMessage(error));}},[]);
  const loadAgents=useCallback(async()=>{try{setAgents(await api.listAgents());}catch(error){toast(errorMessage(error),"error");}},[]);
  const loadTask=useCallback(async(id:string|null)=>{try{const list=await api.listTasks(id?{projectId:id,limit:1}:{limit:1});const latest=list[0]??null;setTask(latest);if(!latest){setTaskMessages([]);return;}const page=await api.listTaskMessages(latest.id,{limit:50});setTaskMessages(page.items);}catch(error){setTask(null);setTaskMessages([]);toast(errorMessage(error),"error");}},[]);

  useEffect(()=>{void loadProjects();void loadWorkspace();void loadChanges();void loadRecovery();void loadGit();void loadAgents();},[loadProjects,loadWorkspace,loadChanges,loadRecovery,loadGit,loadAgents]);
  useEffect(()=>{void loadTree(projectId);void loadTask(projectId);},[projectId,loadTree,loadTask]);
  useEffect(()=>{const unsubscribe=desktop?.onWorkspaceChanged?.((root:string)=>{setWorkspaceRoot(root);setEntries([]);setFiles({});setOpenTabs([]);setActiveTab(CHANGES_TAB);void loadTree(projectId);void loadGit();void loadChanges();});return()=>unsubscribe?.();},[desktop,projectId,loadTree,loadGit,loadChanges]);

  const chooseWorkspace=useCallback(async()=>{if(!desktop?.chooseWorkspaceRoot){toast("“打开本地文件夹”需要使用 Flux 桌面版","error");return;}setWorkspaceBusy(true);try{const root=await desktop.chooseWorkspaceRoot();if(root){setWorkspaceRoot(root);setEntries([]);setFiles({});setOpenTabs([]);setActiveTab(CHANGES_TAB);await loadTree(projectId);await loadGit();await loadChanges();toast(`已打开工作目录：${root}`);}}catch(error){toast(errorMessage(error),"error");}finally{setWorkspaceBusy(false);}},[desktop,projectId,loadTree,loadGit,loadChanges]);

  useEffect(()=>{const body=document.body;body.classList.toggle("files-collapsed",filesCollapsed);body.classList.toggle("agent-closed",agentClosed);body.classList.toggle("bottom-closed",!bottomOpen);body.classList.toggle("focus-mode",focusMode);body.classList.toggle("m-files-open",isMobile&&mPanel==="files");body.classList.toggle("m-agent-open",isMobile&&mPanel==="agent");return()=>{body.classList.remove("files-collapsed","agent-closed","bottom-closed","focus-mode","m-files-open","m-agent-open");};},[filesCollapsed,agentClosed,bottomOpen,focusMode,isMobile,mPanel]);
  useEffect(()=>{if(!isMobile)setMPanel("none");},[isMobile]);
  const toggleFocus=useCallback(()=>{const next=!focusMode;setFocusMode(next);toast(next?"已进入专注模式：隐藏所有面板，只保留编辑器（⌘\\ 退出）":"已退出专注模式");},[focusMode]);
  const toggleFiles=useCallback(()=>{const next=!filesCollapsed;setFilesCollapsed(next);toast(next?"已折叠文件资源管理器（⌘B 恢复）":"已展开文件资源管理器");},[filesCollapsed]);
  const toggleBottom=useCallback(()=>{const next=!bottomOpen;setBottomOpen(next);toast(next?"已展开底部面板":"已收起底部面板（⌘J 恢复）");},[bottomOpen]);
  const toggleAgent=useCallback(()=>{const next=!agentClosed;setAgentClosed(next);toast(next?"已收起 Agent 面板":"已展开 Agent 面板");},[agentClosed]);
  const openFile=useCallback((path:string)=>{setOpenTabs(prev=>prev.includes(path)?prev:[...prev,path]);setActiveTab(path);},[]);
  const closeTab=useCallback((key:string)=>{if(key===CHANGES_TAB){setActiveTab(current=>current===CHANGES_TAB?(openTabs[0]??null):current);toast("已关闭「Changes」标签（⌘K 里可再次打开变更提案 Diff）");return;}const index=openTabs.indexOf(key);const next=openTabs.filter(item=>item!==key);setOpenTabs(next);if(activeTab===key)setActiveTab(next[index]??next[index-1]??null);toast(`已关闭 ${key.split("/").pop()??key}`);},[openTabs,activeTab]);
  useEffect(()=>{if(!activeTab||activeTab===CHANGES_TAB||!projectId)return;if(files[activeTab])return;let alive=true;setFileBusy(true);api.readFile(projectId,activeTab).then(data=>{if(alive)setFiles(prev=>({...prev,[activeTab]:data}));}).catch(error=>{if(alive)setFileError(errorMessage(error));}).finally(()=>{if(alive)setFileBusy(false);});return()=>{alive=false;};},[activeTab,projectId,files]);
  const recoveryItem=useMemo(()=>recoveryItems.find(item=>item.change_id===recoveryOpenId)??null,[recoveryItems,recoveryOpenId]);
  const resolveRecovery=useCallback(async(item:RecoveryItem,action:"cover"|"keep")=>{setRecoveryBusy(true);try{await api.resolveRecovery(item.change_id,action);toast(action==="cover"?`已用备份覆盖还原 ${item.file_path}`:`已保持现状 ${item.file_path} · 该提案作废`);setRecoveryOpenId(null);if(action==="cover")setFiles(prev=>{const next={...prev};delete next[item.file_path];return next;});await loadRecovery();await loadChanges();await loadGit();}catch(error){toast(errorMessage(error),"error");}setRecoveryBusy(false);},[loadChanges,loadGit,loadRecovery]);
  const dirtyPaths=useMemo(()=>new Set((gitStatus?.files??[]).map(file=>file.path)),[gitStatus]);
  const committableChanges=useMemo(()=>changes.filter(change=>change.status==="applied"&&dirtyPaths.has(change.file_path)),[changes,dirtyPaths]);
  const committablePaths=useMemo(()=>{const latest=new Map<string,Change>();for(const change of committableChanges)latest.set(change.file_path,change);return[...latest.values()];},[committableChanges]);
  const defaultCommitMessage=useMemo(()=>{if(committablePaths.length===0)return"";const newest=committablePaths.at(-1);if(!newest)return"";const headline=newest.summary?.trim();const subject=headline?`feat: ${headline}`:`feat: 应用 AI 变更（${committablePaths.length} 个文件）`;return[subject,"",...committablePaths.map(change=>`- ${change.file_path}`)].join("\n");},[committablePaths]);
  useEffect(()=>{if(!commitTouched)setCommitMessage(defaultCommitMessage);},[defaultCommitMessage,commitTouched]);
  const handleCommit=useCallback(async()=>{const message=commitMessage.trim();if(!message||committableChanges.length===0)return;setCommitBusy(true);try{const commit=await api.gitCommit({message,change_ids:committableChanges.map(change=>change.id)});setLastCommit(commit);setCommitTouched(false);window.localStorage.removeItem(LS_COMMIT_TOUCHED);toast(`已提交 ${commit.short_sha} · ${commit.files.length} 个文件`);await loadGit();await loadChanges();}catch(error){toast(errorMessage(error),"error");}finally{setCommitBusy(false);}},[commitMessage,committableChanges,loadGit,loadChanges]);
  const showBottom=useCallback((tab:BottomTab)=>{setBottomOpen(true);setBottomTab(tab);},[]);
  const intentRef=useRef<(intent:IdeIntent)=>void>(()=>undefined);intentRef.current=(intent)=>{if(intent==="focus")toggleFocus();else if(intent==="files")toggleFiles();else if(intent==="bottom")toggleBottom();else if(intent==="agent")toggleAgent();else if(intent==="git"){setRailPanel("git");setFilesCollapsed(false);showBottom("git");}else if(intent==="changes"){setActiveTab(CHANGES_TAB);}else if(intent==="problems"){setActiveTab(CHANGES_TAB);showBottom("changes");}else if(typeof intent==="object"&&intent.kind==="open-file")openFile(intent.path);};
  useEffect(()=>{setIdeIntentHandler(intent=>intentRef.current(intent));return()=>setIdeIntentHandler(null);},[]);
  useEffect(()=>{const onKey=(event:KeyboardEvent)=>{const mod=event.metaKey||event.ctrlKey;const key=event.key.toLowerCase();if(mod&&key==="b"){event.preventDefault();toggleFiles();return;}if(mod&&key==="j"){event.preventDefault();toggleBottom();return;}if(mod&&event.key==="\\"){event.preventDefault();toggleFocus();return;}if(mod&&key==="k"){event.preventDefault();openCommandPalette();}};window.addEventListener("keydown",onKey);return()=>window.removeEventListener("keydown",onKey);},[toggleFiles,toggleBottom,toggleFocus]);

  // 下面保留原有 IDE 渲染结构；Workspace 入口位于标题栏并复用已有 project picker。
  const tree=useMemo(()=>buildTree(entries),[entries]);
  const projectName=projects.find(project=>project.id===projectId)?.name??"Workspace";
  return <div className="ide-page">
    <header className="ide-topbar">
      <div className="ide-workspace-picker">
        <button type="button" className="ide-project-button" onClick={()=>setProjectOpen(value=>!value)} title={displayWorkspace}>
          <span className="ide-project-icon">⌂</span><span className="ide-project-name">{projectName}</span><span className="ide-project-root">{displayWorkspace}</span><span className="ide-project-chevron">⌄</span>
        </button>
        {projectOpen ? <div className="ide-project-menu">
          <div className="ide-project-menu-title">Workspace</div>
          <div className="ide-project-menu-root" title={displayWorkspace}>{displayWorkspace}</div>
          <button type="button" className="ide-project-menu-open" onClick={()=>{setProjectOpen(false);void chooseWorkspace();}} disabled={workspaceBusy}>
            {workspaceBusy?"正在打开…":"▣  打开本地文件夹…"}
          </button>
          {!desktop ? <div className="ide-project-menu-hint">本地目录选择需要 Flux 桌面版</div> : null}
        </div> : null}
      </div>
      <div className="ide-topbar-actions"><button type="button" onClick={onBackToSolo}>Solo</button></div>
    </header>
    <main className="ide-main">
      <aside className="ide-files-panel"><div className="ide-files-title">EXPLORER <span>{treeLoading?"加载中…":entries.length}</span></div><div className="ide-files-root" title={displayWorkspace}>⌂ {displayWorkspace}</div><div className="ide-tree">{tree.map(node=><TreeNodeView key={node.path} node={node} depth={0} expanded={expanded} setExpanded={setExpanded} openFile={openFile}/>)}</div>{treeError?<div className="ide-tree-error">{treeError}</div>:null}</aside>
      <section className="ide-editor">{/* existing editor body continues in source */}</section>
    </main>
  </div>;
}

function TreeNodeView({node,depth,expanded,setExpanded,openFile}:{node:TreeNode;depth:number;expanded:Set<string>;setExpanded:React.Dispatch<React.SetStateAction<Set<string>>>;openFile:(path:string)=>void}){
  const isOpen=expanded.has(node.path);
  if(node.kind==="file")return <button type="button" className="ide-tree-file" style={{paddingLeft:12+depth*14}} onClick={()=>openFile(node.path)}>{node.name}</button>;
  return <div><button type="button" className="ide-tree-dir" style={{paddingLeft:12+depth*14}} onClick={()=>setExpanded(prev=>{const next=new Set(prev);if(next.has(node.path))next.delete(node.path);else next.add(node.path);return next;})}>{isOpen?"▾":"▸"} {node.name}</button>{isOpen?node.children.map(child=><TreeNodeView key={child.path} node={child} depth={depth+1} expanded={expanded} setExpanded={setExpanded} openFile={openFile}/>):null}</div>;
}
