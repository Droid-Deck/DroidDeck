#include "buffer_commit_sync.hpp"

#include <cassert>
#include <cstdio>
#include <optional>
#include <memory>
#include <vector>

struct DoneCommits {};

struct MockCommit
{
	void SetFence( int newFence, bool newMangoNudge, unsigned int newMangoMsgType,
		DoneCommits *newDoneCommits )
	{
		setFenceCalls++;
		fence = newFence;
		mangoNudge = newMangoNudge;
		mangoMsgType = newMangoMsgType;
		doneCommits = newDoneCommits;
	}

	void Signal()
	{
		signalCalls++;
	}

	int setFenceCalls = 0;
	unsigned long commitID = 1;
	int signalCalls = 0;
	int fence = -2;
	bool mangoNudge = false;
	unsigned int mangoMsgType = 0;
	DoneCommits *doneCommits = nullptr;
};

struct MockWaiter
{
	bool AddWaitable( MockCommit *newCommit )
	{
		addCalls++;
		commit = newCommit;
		return addResult;
	}

	bool addResult = true;
	int addCalls = 0;
	MockCommit *commit = nullptr;
};

struct MockBuffer
{
	std::optional<int> GetDmaBufFence()
	{
		dmaBufQueries++;
		return dmaBufFence;
	}

	int ExportMemoryFd()
	{
		memoryFdExports++;
		return 23;
	}

	std::optional<int> dmaBufFence;
	int dmaBufQueries = 0;
	int memoryFdExports = 0;
};

static void TestSynchronousShmUploadSignalsImmediately()
{
	DoneCommits doneCommits;
	MockCommit commit;
	MockWaiter waiter;
	MockBuffer buffer;
	const gamescope::BufferCommitSync sync = gamescope::SelectBufferCommitSync(
		false, -1, false, [&]() { return buffer.GetDmaBufFence(); } );

	assert( sync.source == gamescope::BufferCommitSyncSource::SynchronousUpload );
	assert( sync.fence == -1 );
	assert( sync.knownReady );
	assert( gamescope::DispatchBufferCommitSync( &commit, waiter, sync, true, 7, &doneCommits ) );
	assert( commit.setFenceCalls == 1 );
	assert( commit.fence == -1 );
	assert( commit.signalCalls == 1 );
	assert( waiter.addCalls == 0 );
	assert( buffer.dmaBufQueries == 1 );
	assert( buffer.memoryFdExports == 0 );
	assert( commit.mangoNudge );
	assert( commit.mangoMsgType == 7 );
	assert( commit.doneCommits == &doneCommits );
}

static void TestExplicitAcquireRetainsReadyState()
{
	DoneCommits doneCommits;
	MockCommit pendingCommit;
	MockWaiter pendingWaiter;
	MockBuffer pendingBuffer;
	const gamescope::BufferCommitSync pending = gamescope::SelectBufferCommitSync(
		true, 17, false, [&]() { return pendingBuffer.GetDmaBufFence(); } );
	assert( gamescope::DispatchBufferCommitSync(
		&pendingCommit, pendingWaiter, pending, false, 0, &doneCommits ) );
	assert( pendingCommit.fence == 17 );
	assert( pendingCommit.signalCalls == 0 );
	assert( pendingWaiter.addCalls == 1 );
	assert( pendingBuffer.dmaBufQueries == 0 );

	MockCommit readyCommit;
	MockWaiter readyWaiter;
	MockBuffer readyBuffer;
	const gamescope::BufferCommitSync ready = gamescope::SelectBufferCommitSync(
		true, 18, true, [&]() { return readyBuffer.GetDmaBufFence(); } );
	assert( gamescope::DispatchBufferCommitSync(
		&readyCommit, readyWaiter, ready, false, 0, &doneCommits ) );
	assert( readyCommit.fence == 18 );
	assert( readyCommit.signalCalls == 1 );
	assert( readyWaiter.addCalls == 0 );
	assert( readyBuffer.dmaBufQueries == 0 );
}

static void TestDmaBufStillWaits()
{
	DoneCommits doneCommits;
	MockCommit commit;
	MockWaiter waiter;
	MockBuffer buffer;
	buffer.dmaBufFence = 21;
	const gamescope::BufferCommitSync sync = gamescope::SelectBufferCommitSync(
		false, -1, false, [&]() { return buffer.GetDmaBufFence(); } );

	assert( sync.source == gamescope::BufferCommitSyncSource::DmaBuf );
	assert( !sync.knownReady );
	assert( gamescope::DispatchBufferCommitSync( &commit, waiter, sync, false, 0, &doneCommits ) );
	assert( commit.fence == 21 );
	assert( commit.signalCalls == 0 );
	assert( waiter.addCalls == 1 );
	assert( buffer.dmaBufQueries == 1 );
	assert( buffer.memoryFdExports == 0 );
}

static void TestFailedWaitRegistrationDoesNotSignal()
{
	DoneCommits doneCommits;
	MockCommit commit;
	MockWaiter waiter;
	waiter.addResult = false;
	const gamescope::BufferCommitSync sync = gamescope::BufferCommitSync::DmaBuf( 21 );

	assert( !gamescope::DispatchBufferCommitSync( &commit, waiter, sync, false, 0, &doneCommits ) );
	assert( commit.fence == 21 );
	assert( commit.signalCalls == 0 );
	assert( waiter.addCalls == 1 );
}

static void TestFailedDmaBufDupDoesNotWaitOrSignal()
{
	DoneCommits done;
	MockCommit commit;
	MockWaiter waiter;
	const auto sync = gamescope::SelectBufferCommitSync(
		false, -1, false, []() { return std::optional<int>( -1 ); } );
	assert( sync.source == gamescope::BufferCommitSyncSource::DmaBuf );
	assert( !gamescope::DispatchBufferCommitSync( &commit, waiter, sync, false, 0, &done ) );
	assert( commit.signalCalls == 0 );
	assert( waiter.addCalls == 0 );
}

#ifdef DROIDDECK_PRODUCTION_QUEUE_BLOCK
struct MockWindow
{
	std::vector<std::shared_ptr<MockCommit>> commit_queue;
	bool receivedDoneCommit = false;
};
struct MockLog { template<typename... Args> void errorf( const char *, Args... ) {} };

static void QueueProductionCommit( MockWindow *w, std::shared_ptr<MockCommit> newCommit,
	MockWaiter &g_ImageWaiter, const gamescope::BufferCommitSync &commitSync )
{
	MockLog xwm_log;
	DoneCommits done;
	DoneCommits *doneCommits = &done;
	bool mango_nudge = false;
	unsigned int uMangoMsgType = 0;
#include DROIDDECK_PRODUCTION_QUEUE_BLOCK
}

static void TestProductionCallerDropsUnwaitableCommits()
{
	for ( int fd : { -1, 21 } )
	{
		MockWindow window;
		MockWaiter waiter;
		waiter.addResult = false;
		auto commit = std::make_shared<MockCommit>();
		std::weak_ptr<MockCommit> lifetime = commit;
		QueueProductionCommit( &window, std::move( commit ), waiter,
			gamescope::BufferCommitSync::DmaBuf( fd ) );
		assert( window.commit_queue.empty() );
		assert( window.receivedDoneCommit );
		assert( lifetime.expired() );
	}
	MockWindow window;
	MockWaiter waiter;
	auto commit = std::make_shared<MockCommit>();
	QueueProductionCommit( &window, commit, waiter, gamescope::BufferCommitSync::SynchronousUpload() );
	assert( window.commit_queue.size() == 1 );
	assert( commit->signalCalls == 1 );
}
#endif

int main()
{
	TestSynchronousShmUploadSignalsImmediately();
	TestExplicitAcquireRetainsReadyState();
	TestDmaBufStillWaits();
	TestFailedWaitRegistrationDoesNotSignal();
	TestFailedDmaBufDupDoesNotWaitOrSignal();
#ifdef DROIDDECK_PRODUCTION_QUEUE_BLOCK
	TestProductionCallerDropsUnwaitableCommits();
#endif
	std::puts( "Gamescope buffer commit synchronization tests passed" );
}
