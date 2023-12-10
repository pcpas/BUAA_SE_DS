package raft

//
// this is an outline of the API that raft must expose to
// the service (or tester). see comments below for
// each of these functions for more details.
//
// rf = Make(...)
//   create a new Raft server.
// rf.Start(command interface{}) (index, term, isleader)
//   start agreement on a new log entry
// rf.GetState() (term, isLeader)
//   ask a Raft for its current term, and whether it thinks it is leader
// ApplyMsg
//   each time a new entry is committed to the log, each Raft peer
//   should send an ApplyMsg to the service (or tester)
//   in the same server.
//

import (
	//	"bytes"
	"math/rand"
	"sync"
	"sync/atomic"
	"time"

	//	"6.5840/labgob"
	"6.5840/labrpc"
)


//定义常量
const None = -1
const Follower, Candidate, Leader int = 1, 2, 3
const heartbeatTimeout = 150 * time.Millisecond
const tickInterval = 70 * time.Millisecond
const baseElectionTimeout = 300


// as each Raft peer becomes aware that successive log entries are
// committed, the peer should send an ApplyMsg to the service (or
// tester) on the same server, via the applyCh passed to Make(). set
// CommandValid to true to indicate that the ApplyMsg contains a newly
// committed log entry.
//
// in part 2D you'll want to send other kinds of messages (e.g.,
// snapshots) on the applyCh, but set CommandValid to false for these
// other uses.
type ApplyMsg struct {
	CommandValid bool
	Command      interface{}
	CommandIndex int

	// For 2D:
	SnapshotValid bool
	Snapshot      []byte
	SnapshotTerm  int
	SnapshotIndex int
}

type PeerTracker struct {
	nextIndex  int
	matchIndex int

	lastAck time.Time
}

func (rf *Raft) resetTrackedIndex() {

	for i, _ := range rf.peerTrackers {
		if i != rf.me {
			rf.peerTrackers[i].nextIndex = rf.log.LastLogIndex + 1 // 成为了leader，默认nextIndex都是从rf.log.LastLogIndex + 1开始
			rf.peerTrackers[i].matchIndex = 0                      //成为leader时，将其nextIndex和matchIndex置为

			DPrintf(50, "实例 %d 的nextIndex被更新为: %d...", rf.me, rf.peerTrackers[i].nextIndex)

		}
	}
}

type Raft struct {
    mu        sync.Mutex          // Lock to protect shared access to this peer's state
    peers     []*labrpc.ClientEnd // RPC end points of all peers
    persister *Persister          // Object to hold this peer's persisted state
    me        int                 // this peer's index into peers[]
    dead      int32               // set by Kill()
    // Your data here (2A, 2B, 2C).
    // Look at the paper's Figure 2 for a description of what
    // state a Raft server must maintain.
    state            int           // Follower-Candidate-Leader
    currentTerm      int           
    votedFor         int           // index of the one this node vote for
    heartbeatTimeout time.Duration // timer for heartbeat
    electionTimeout  time.Duration // timer for election
    lastElection     time.Time     
    lastHeartbeat    time.Time    
    peerTrackers     []PeerTracker // keeps track of each peer's next index, match index, etc.

    //Lab2B新补充的属性
    log              *Log          // 日志记录
    commitIndex int 
    lastApplied int 
    applyHelper *ApplyHelper
    applyCond   *sync.Cond
}

// return currentTerm and whether this server
// believes it is the leader.
func (rf *Raft) GetState() (int, bool) {

	var term int
	var isleader bool
	// Your code here (2A).
	rf.mu.Lock()
	term = rf.currentTerm
	isleader = rf.state == Leader
	rf.mu.Unlock()

	////DPrintf(110,"getting Leader State %d and term %d of node %d \n", rf.state, rf.currentTerm, rf.me)
	return term, isleader
}
// save Raft's persistent state to stable storage,
// where it can later be retrieved after a crash and restart.
// see paper's Figure 2 for a description of what should be persistent.
// before you've implemented snapshots, you should pass nil as the
// second argument to persister.Save().
// after you've implemented snapshots, pass the current snapshot
// (or nil if there's not yet a snapshot).
func (rf *Raft) persist() {
	// Your code here (2C).
	// Example:
	// w := new(bytes.Buffer)
	// e := labgob.NewEncoder(w)
	// e.Encode(rf.xxx)
	// e.Encode(rf.yyy)
	// raftstate := w.Bytes()
	// rf.persister.Save(raftstate, nil)
}


// restore previously persisted state.
func (rf *Raft) readPersist(data []byte) {
	if data == nil || len(data) < 1 { // bootstrap without any state?
		return
	}
	// Your code here (2C).
	// Example:
	// r := bytes.NewBuffer(data)
	// d := labgob.NewDecoder(r)
	// var xxx
	// var yyy
	// if d.Decode(&xxx) != nil ||
	//    d.Decode(&yyy) != nil {
	//   error...
	// } else {
	//   rf.xxx = xxx
	//   rf.yyy = yyy
	// }
}


// the service says it has created a snapshot that has
// all info up to and including index. this means the
// service no longer needs the log through (and including)
// that index. Raft should now trim its log as much as possible.
func (rf *Raft) Snapshot(index int, snapshot []byte) {
	// Your code here (2D).

}



// example RequestVote RPC arguments structure.
// field names must start with capital letters!
type RequestVoteArgs struct {
    // Your data here (2A, 2B).
    Term        int // candidate’s term
    CandidateId int //candidate requesting vote

    LastLogIndex int // index of candidate’s last log entry (§5.4)
    LastLogTerm  int //term of candidate’s last log entry
}

// example RequestVote RPC reply structure.
// field names must start with capital letters!
type RequestVoteReply struct {
    // Your data here (2A).
    Term        int
    VoteGranted bool
}


// example RequestVoteRPC handler.
func (rf *Raft) RequestVote(args *RequestVoteArgs, reply *RequestVoteReply) {
	// Your code here (2A, 2B).
	rf.mu.Lock()
	defer rf.mu.Unlock()
	reply.VoteGranted = true // 默认设置响应体为投同意票状态
	reply.Term = rf.currentTerm
	//竞选leader的节点任期小于等于自己的任期，则反对票(为什么等于情况也反对票呢？因为candidate节点在发送requestVote rpc之前会将自己的term+1)
	if args.Term < rf.currentTerm {
		reply.VoteGranted = false
		return
	}
	if args.Term > rf.currentTerm {
		rf.currentTerm = args.Term
		reply.Term = rf.currentTerm
		rf.votedFor = None
		rf.state = Follower
	}

	// candidate节点发送过来的日志索引以及任期必须大于等于自己的日志索引及任期
	update := false
	update = update || args.LastLogTerm > rf.getLastEntryTerm()
	update = update || args.LastLogTerm == rf.getLastEntryTerm() && args.LastLogIndex >= rf.log.LastLogIndex

	if (rf.votedFor == -1 || rf.votedFor == args.CandidateId) && update {
		//if rf.votedFor == -1 {
		//竞选任期大于自身任期，则更新自身任期，并转为follower
		rf.votedFor = args.CandidateId
		rf.state = Follower
		rf.resetElectionTimer() //自己的票已经投出时就转为follower状态
	} else {
		reply.VoteGranted = false
	}
}


// example code to send a RequestVote RPC to a server.
// server is the index of the target server in rf.peers[].
// expects RPC arguments in args.
// fills in *reply with RPC reply, so caller should
// pass &reply.
// the types of the args and reply passed to Call() must be
// the same as the types of the arguments declared in the
// handler function (including whether they are pointers).
//
// The labrpc package simulates a lossy network, in which servers
// may be unreachable, and in which requests and replies may be lost.
// Call() sends a request and waits for a reply. If a reply arrives
// within a timeout interval, Call() returns true; otherwise
// Call() returns false. Thus Call() may not return for a while.
// A false return can be caused by a dead server, a live server that
// can't be reached, a lost request, or a lost reply.
//
// Call() is guaranteed to return (perhaps after a delay) *except* if the
// handler function on the server side does not return.  Thus there
// is no need to implement your own timeouts around Call().
//
// look at the comments in ../labrpc/labrpc.go for more details.
//
// if you're having trouble getting RPC to work, check that you've
// capitalized all field names in structs passed over RPC, and
// that the caller passes the address of the reply struct with &, not
// the struct itself.
func (rf *Raft) sendRequestVote(server int, args *RequestVoteArgs, reply *RequestVoteReply) bool {
    ok := rf.peers[server].Call("Raft.RequestVote", args, reply)
    return ok
}

// the service using Raft (e.g. a k/v server) wants to start
// agreement on the next command to be appended to Raft's log. if this
// server isn't the leader, returns false. otherwise start the
// agreement and return immediately. there is no guarantee that this
// command will ever be committed to the Raft log, since the leader
// may fail or lose an election. even if the Raft instance has been killed,
// this function should return gracefully.
//
// the first return value is the index that the command will appear at
// if it's ever committed. the second return value is the current
// term. the third return value is true if this server believes it is
// the leader.
func (rf *Raft) Start(command interface{}) (int, int, bool) {
	index := -1
	term := -1
	isLeader := true
	// Your code here (2B).
	rf.mu.Lock()
	defer rf.mu.Unlock()
	term = rf.currentTerm
	if rf.state != Leader {
		isLeader = false
		return index, term, isLeader
	}
	index = rf.log.LastLogIndex + 1
	// 开始发送AppendEntries rpc
	rf.log.appendL(Entry{term, command})
	//rf.resetTrackedIndex()
	go rf.StartAppendEntries(false)

	return index, term, isLeader
}

// the tester doesn't halt goroutines created by Raft after each test,
// but it does call the Kill() method. your code can use killed() to
// check whether Kill() has been called. the use of atomic avoids the
// need for a lock.
//
// the issue is that long-running goroutines use memory and may chew
// up CPU time, perhaps causing later tests to fail and generating
// confusing debug output. any goroutine with a long-running loop
// should call killed() to check whether it should stop.
func (rf *Raft) Kill() {
	atomic.StoreInt32(&rf.dead, 1)

	// Your code here, if desired.
	rf.mu.Lock()
	defer rf.mu.Unlock()
	rf.applyHelper.Kill()
	rf.state = Follower
}


func (rf *Raft) killed() bool {
	z := atomic.LoadInt32(&rf.dead)
	return z == 1
}

func (rf *Raft) ticker() {
	for !rf.killed() {
		rf.mu.Lock()
		state := rf.state
		rf.mu.Unlock()
		switch state {
		case Follower:
			fallthrough // 相当于执行#A到#C代码块,

		case Candidate:
			if rf.pastElectionTimeout() { 
				rf.StartElection()
			} 
		case Leader:
			// 只有Leader节点才能发送心跳和日志给从节点
			isHeartbeat := false
			// 检测是需要发送单纯的心跳还是发送日志
			// 心跳定时器过期则发送心跳，否则发送日志
			if rf.pastHeartbeatTimeout() {
				isHeartbeat = true
				rf.resetHeartbeatTimer()
				rf.StartAppendEntries(isHeartbeat)
			}
			rf.StartAppendEntries(isHeartbeat)

		}
		time.Sleep(tickInterval)
	}
}


func (rf *Raft) pastElectionTimeout() bool {
	rf.mu.Lock()
	defer rf.mu.Unlock()
	return time.Since(rf.lastElection) > rf.electionTimeout
}

func (rf *Raft) pastHeartbeatTimeout() bool {
    rf.mu.Lock()
    defer rf.mu.Unlock()
    return time.Since(rf.lastHeartbeat) > rf.heartbeatTimeout
}

type RequestAppendEntriesArgs struct {
    LeaderTerm   int // Leader的Term
    LeaderId     int
    PrevLogIndex int // 新日志条目的上一个日志的索引
    PrevLogTerm  int // 新日志的上一个日志的任期
    //Logs         []ApplyMsg // 需要被保存的日志条目,可能有多个
    Entries      []Entry
    LeaderCommit int // Leader已提交的最高的日志项目的索引
}


type RequestAppendEntriesReply struct {
    FollowerTerm int  // Follower的Term,给Leader更新自己的Term
    Success      bool // 是否推送成功
    PrevLogIndex int
    PrevLogTerm  int
}

func (rf *Raft) StartAppendEntries(heart bool) {
    // 并行向其他节点发送心跳或者日志，让他们知道此刻已经有一个leader产生
    rf.mu.Lock()
    defer rf.mu.Unlock()
    if rf.state != Leader {
        return
    }
    rf.resetElectionTimer()
    for i, _ := range rf.peers {
        if i == rf.me {
            continue
        }

        go rf.AppendEntries(i, heart)

    }
}

// nextIndex收敛速度优化：nextIndex跳跃算法
func (rf *Raft) AppendEntries(targetServerId int, heart bool) {
    //rf.mu.Lock()
    //defer rf.mu.Unlock()

    if heart {
        rf.mu.Lock()
        if rf.state != Leader {
            rf.mu.Unlock() //必须解锁，否则会造成死锁
            return
        }
        reply := RequestAppendEntriesReply{}
        args := RequestAppendEntriesArgs{}
        args.LeaderTerm = rf.currentTerm
        args.LeaderId = rf.me
        rf.mu.Unlock()
        ok := rf.sendRequestAppendEntries(true, targetServerId, &args, &reply)
        if !ok {
            return
        }
        if reply.Success {
            // 返回成功则说明接收了心跳
            return
        }
        rf.mu.Lock()
        if rf.state != Leader {
            rf.mu.Unlock()
            return
        }
        if reply.FollowerTerm < rf.currentTerm {
            rf.mu.Unlock()
            return
        }
        // 拒绝接收心跳，则可能是因为任期导致的
        if reply.FollowerTerm > rf.currentTerm {
            rf.votedFor = None
            rf.state = Follower
            rf.currentTerm = reply.FollowerTerm
        }
        rf.mu.Unlock()
        return
    } else {
        rf.mu.Lock()
        if rf.state != Leader {
            rf.mu.Unlock()
            return
        }
        args := RequestAppendEntriesArgs{}
        args.PrevLogIndex = min(rf.log.LastLogIndex, rf.peerTrackers[targetServerId].nextIndex-1)
        if args.PrevLogIndex+1 < rf.log.FirstLogIndex {
            return
        }
        args.LeaderTerm = rf.currentTerm
        args.LeaderId = rf.me
        args.LeaderCommit = rf.commitIndex
        args.PrevLogTerm = rf.getEntryTerm(args.PrevLogIndex)
        args.Entries = rf.log.getAppendEntries(args.PrevLogIndex + 1)
        rf.mu.Unlock()

        reply := RequestAppendEntriesReply{}

        ok := rf.sendRequestAppendEntries(false, targetServerId, &args, &reply)
        if !ok {
            return
        }
        rf.mu.Lock()
        defer rf.mu.Unlock()
        if rf.state != Leader {
            //rf.mu.Unlock()
            return
        }
        // 丢弃旧的rpc响应
        if reply.FollowerTerm < rf.currentTerm {
            return
        }

        if reply.FollowerTerm > rf.currentTerm {
            rf.state = Follower
            rf.currentTerm = reply.FollowerTerm
            rf.votedFor = None
            return
        }
        if reply.Success {
            rf.peerTrackers[targetServerId].nextIndex = args.PrevLogIndex + len(args.Entries) + 1
            rf.peerTrackers[targetServerId].matchIndex = args.PrevLogIndex + len(args.Entries)
            rf.tryCommitL(rf.peerTrackers[targetServerId].matchIndex)
            return
        }

        if rf.log.empty() { //判掉为空的情况 方便后面讨论
            return
        }
        if reply.PrevLogIndex+1 < rf.log.FirstLogIndex {
            return
        }
        
        if reply.PrevLogIndex > rf.log.LastLogIndex {
            rf.peerTrackers[targetServerId].nextIndex = rf.log.LastLogIndex + 1
        } else if rf.getEntryTerm(reply.PrevLogIndex) == reply.PrevLogTerm {
            // 因为响应方面接收方做了优化，作为响应方的从节点可以直接跳到索引不匹配但是等于任期PrevLogTerm的第一个提交的日志记录
            rf.peerTrackers[targetServerId].nextIndex = reply.PrevLogIndex + 1
        } else {
            // 此时rf.getEntryTerm(reply.PrevLogIndex) != reply.PrevLogTerm，也就是说此时索引相同位置上的日志提交时所处term都不同，
            // 则此日志也必然是不同的，所以可以安排跳到前一个当前任期的第一个节点
            PrevIndex := reply.PrevLogIndex
            for PrevIndex >= rf.log.FirstLogIndex && rf.getEntryTerm(PrevIndex) == rf.getEntryTerm(reply.PrevLogIndex) {
                PrevIndex--
            }
            rf.peerTrackers[targetServerId].nextIndex = PrevIndex + 1
        }
    }
}


func (rf *Raft) sendRequestAppendEntries(isHeartbeat bool, server int, args *RequestAppendEntriesArgs, reply *RequestAppendEntriesReply) bool {
    var ok bool
    if isHeartbeat {
        ok = rf.peers[server].Call("Raft.HandleHeartbeatRPC", args, reply)
    } else {
        ok = rf.peers[server].Call("Raft.HandleAppendEntriesRPC", args, reply)
    }
    return ok
}


func (rf *Raft) HandleHeartbeatRPC(args *RequestAppendEntriesArgs, reply *RequestAppendEntriesReply) {
    rf.mu.Lock() // 加接收心跳方的锁
    defer rf.mu.Unlock()
    reply.FollowerTerm = rf.currentTerm
    reply.Success = true
    // 旧任期的leader抛弃掉
    if args.LeaderTerm < rf.currentTerm {
        reply.Success = false
        reply.FollowerTerm = rf.currentTerm
        return
    }
    rf.resetElectionTimer()
    // 需要转变自己的身份为Follower
    rf.state = Follower
    rf.votedFor = args.LeaderId

    // 承认来者是个合法的新leader，则任期一定大于自己，此时需要设置votedFor为-1以及
    if args.LeaderTerm > rf.currentTerm {
        rf.votedFor = None
        rf.currentTerm = args.LeaderTerm
        reply.FollowerTerm = rf.currentTerm
    }
    // 重置自身的选举定时器，这样自己就不会重新发出选举需求（因为它在ticker函数中被阻塞住了）
}

// nextIndex收敛速度优化：nextIndex跳跃算法
func (rf *Raft) HandleAppendEntriesRPC(args *RequestAppendEntriesArgs, reply *RequestAppendEntriesReply) {
    rf.mu.Lock() // 加接收日志方的锁
    defer rf.mu.Unlock()
    reply.FollowerTerm = rf.currentTerm
    reply.Success = true
    // 旧任期的leader抛弃掉
    if args.LeaderTerm < rf.currentTerm {
        reply.Success = false
        return
    }
    rf.resetElectionTimer()
    rf.state = Follower // 需要转变自己的保持为Follower

    if args.LeaderTerm > rf.currentTerm {
        rf.votedFor = None // 调整votedFor为-1
        rf.currentTerm = args.LeaderTerm
        reply.FollowerTerm = rf.currentTerm
    }

    if args.PrevLogIndex+1 < rf.log.FirstLogIndex || args.PrevLogIndex > rf.log.LastLogIndex {
        reply.FollowerTerm = rf.currentTerm
        reply.Success = false
        reply.PrevLogIndex = rf.log.LastLogIndex
        reply.PrevLogTerm = rf.getLastEntryTerm()
    } else if rf.getEntryTerm(args.PrevLogIndex) == args.PrevLogTerm {
        ok := true
        for i, entry := range args.Entries {
            index := args.PrevLogIndex + 1 + i
            if index > rf.log.LastLogIndex {
                rf.log.appendL(entry)
            } else if rf.log.getOneEntry(index).Term != entry.Term {
                // 采用覆盖写的方式
                ok = false
                *rf.log.getOneEntry(index) = entry
            }
        }
        if !ok {
            rf.log.LastLogIndex = args.PrevLogIndex + len(args.Entries)
        }
        if args.LeaderCommit > rf.commitIndex {
            if args.LeaderCommit < rf.log.LastLogIndex {
                rf.commitIndex = args.LeaderCommit
            } else {
                rf.commitIndex = rf.log.LastLogIndex
            }
            rf.applyCond.Broadcast()
        }
        reply.FollowerTerm = rf.currentTerm
        reply.Success = true
        reply.PrevLogIndex = rf.log.LastLogIndex
        reply.PrevLogTerm = rf.getLastEntryTerm()
    } else {
        prevIndex := args.PrevLogIndex
        for prevIndex >= rf.log.FirstLogIndex && rf.getEntryTerm(prevIndex) == rf.log.getOneEntry(args.PrevLogIndex).Term {
            prevIndex--
        }
        reply.FollowerTerm = rf.currentTerm
        reply.Success = false
        if prevIndex >= rf.log.FirstLogIndex {
            reply.PrevLogIndex = prevIndex
            reply.PrevLogTerm = rf.getEntryTerm(prevIndex)
        }
    }
}


func (rf *Raft) StartElection() {
	rf.mu.Lock()
	defer rf.mu.Unlock()
	if rf.state == Leader {
		// 如果是leader，则应该不应该进行选举
		return
	}
	rf.becomeCandidate()
	term := rf.currentTerm
	done := false
	votes := 1
	args := RequestVoteArgs{}
	args.Term = rf.currentTerm
	args.CandidateId = rf.me
	args.LastLogIndex = rf.log.LastLogIndex
	args.LastLogTerm = rf.getLastEntryTerm()
	for i, _ := range rf.peers {
		if rf.me == i {
			continue
		}
		// 开启协程去尝试拉选票
		go func(serverId int) {
			var reply RequestVoteReply
			ok := rf.sendRequestVote(serverId, &args, &reply)
			if !ok || !reply.VoteGranted {
				return
			}

			rf.mu.Lock()
			defer rf.mu.Unlock()
			if reply.Term < rf.currentTerm {
				return
			}
			// 统计票数
			votes++
			if done || votes <= len(rf.peers)/2 {
				// 在成为leader之前如果投票数不足需要继续收集选票
				// 同时在成为leader的那一刻，就不需要管剩余节点的响应了
				return
			}
			done = true
			if rf.state != Candidate || rf.currentTerm != term {
				return
			}
			rf.becomeLeader()
		}(i)
	}
}

func (rf *Raft) becomeLeader() {
	rf.state = Leader
	rf.resetTrackedIndex()
}

func (rf *Raft) resetElectionTimer() {
	electionTimeout := baseElectionTimeout + (rand.Int63() % baseElectionTimeout)
	rf.electionTimeout = time.Duration(electionTimeout) * time.Millisecond
	rf.lastElection = time.Now()
}

func (rf *Raft) becomeCandidate() {
	rf.state = Candidate
	rf.currentTerm++
	rf.votedFor = rf.me
	rf.resetElectionTimer()
}

func (rf *Raft) getLastEntryTerm() int {
	if rf.log.LastLogIndex >= rf.log.FirstLogIndex {
		return rf.log.getOneEntry(rf.log.LastLogIndex).Term
	}

	return -1

}


// 主节点对日志进行提交，其条件是多余一半的从节点的commitIndex>=leader节点当前提交的commitIndex
func (rf *Raft) tryCommitL(matchIndex int) {

	if matchIndex <= rf.commitIndex {
		// 首先matchIndex应该是大于leader节点的commitIndex才能提交，因为commitIndex及其之前的不需要更新
		return
	}
	// 越界的也不能提交
	if matchIndex > rf.log.LastLogIndex {
		return
	}
	if matchIndex < rf.log.FirstLogIndex {
		return
	}
	// 提交的必须本任期内从客户端收到的日志
	if rf.getEntryTerm(matchIndex) != rf.currentTerm {
		return
	}

	// 计算所有已经正确匹配该matchIndex的从节点的票数
	cnt := 1 //自动计算上leader节点的一票
	for i := 0; i < len(rf.peers); i++ {
		if i == rf.me {
			continue
		}
		// 为什么只需要保证提交的matchIndex必须小于等于其他节点的matchIndex就可以认为这个节点在这个matchIndex记录上正确匹配呢？
		// 因为matchIndex是增量的，如果一个从节点的matchIndex=10，则表示该节点从1到9的子日志都和leader节点对上了
		if matchIndex <= rf.peerTrackers[i].matchIndex {
			cnt++
		}
	}
	// 超过半数就提交
	if cnt > len(rf.peers)/2 {
		rf.commitIndex = matchIndex
		if rf.commitIndex > rf.log.LastLogIndex {
			panic("commitIndex > lastlogindex")
		}
		rf.applyCond.Broadcast() // 通知对应的applier协程将日志放到状态机上验证
	}
}

// the service or tester wants to create a Raft server. the ports
// of all the Raft servers (including this one) are in peers[]. this
// server's port is peers[me]. all the servers' peers[] arrays
// have the same order. persister is a place for this server to
// save its persistent state, and also initially holds the most
// recent saved state, if any. applyCh is a channel on which the
// tester or service expects Raft to send ApplyMsg messages.
// Make() must return quickly, so it should start goroutines
// for any long-running work.
func Make(peers []*labrpc.ClientEnd, me int,
	persister *Persister, applyCh chan ApplyMsg) *Raft {
	rf := &Raft{}
	rf.peers = peers
	rf.persister = persister
	rf.me = me
	// Your initialization code here (2A, 2B, 2C).
	rf.currentTerm = 0
	rf.votedFor = None
	rf.state = Follower //设置节点的初始状态为follower
	rf.resetElectionTimer()
	rf.heartbeatTimeout = heartbeatTimeout // 这个是固定的
	// initialize from state persisted before a crash
	rf.readPersist(persister.ReadRaftState())
	rf.log = NewLog()
	rf.applyHelper = NewApplyHelper(applyCh, rf.lastApplied)
	rf.commitIndex = 0
	rf.lastApplied = 0
	rf.peerTrackers = make([]PeerTracker, len(rf.peers)) //对等节点追踪器
	rf.applyCond = sync.NewCond(&rf.mu)

	//Leader选举协程
	go rf.ticker()
	go rf.sendMsgToTester() // 供config协程追踪日志以测试

	return rf
}

// 通知tester接收这个日志消息，然后供测试使用
func (rf *Raft) sendMsgToTester() {
	rf.mu.Lock()
	defer rf.mu.Unlock()
	for !rf.killed() {
		rf.applyCond.Wait()

		for rf.lastApplied+1 <= rf.commitIndex {
			i := rf.lastApplied + 1
			rf.lastApplied++
			if i < rf.log.FirstLogIndex {
				panic("error happening")
			}
			msg := ApplyMsg{
				CommandValid: true,
				Command:      rf.log.getOneEntry(i).Command,
				CommandIndex: i,
			}
			rf.applyHelper.tryApply(&msg)
		}
	}
}

func (rf *Raft) resetHeartbeatTimer() {
    rf.mu.Lock()
    defer rf.mu.Unlock()
    rf.lastHeartbeat = time.Now()
}
