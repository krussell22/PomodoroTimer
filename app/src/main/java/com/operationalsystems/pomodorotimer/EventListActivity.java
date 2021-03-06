package com.operationalsystems.pomodorotimer;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.firebase.ui.auth.AuthUI;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.operationalsystems.pomodorotimer.data.Event;
import com.operationalsystems.pomodorotimer.data.PomodoroFirebaseHelper;
import com.operationalsystems.pomodorotimer.data.Team;
import com.operationalsystems.pomodorotimer.data.TeamMember;
import com.operationalsystems.pomodorotimer.data.User;
import com.operationalsystems.pomodorotimer.util.Promise;

import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnItemSelected;

/**
 * Front screen activity that shows events that are in progress
 * or recently completed.
 */
public class EventListActivity extends AppCompatActivity {

    public static final String EXTRA_EVENT_ID = "SelectedEventId";
    public static final String STORE_TEAM_DOMAIN = "TeamDomain";

    // some sync adapter stuff
    public static final String SYNC_AUTHORITY = "com.operationalsystems.pomodorotimer";
    public static final String SYNC_ACCOUNT_TYPE = "pomodorotimer.operationalsystems.com";
    public static final String SYNC_ACCOUNT = "dummy";

    private static final EnumSet<TeamMember.Role> VALID_MEMBERS = EnumSet.of(TeamMember.Role.Owner, TeamMember.Role.Admin, TeamMember.Role.Member);

    /**
     * Local class representing an item that is displayed in
     * the team selection drop down. The items "Personal Activities"
     * and "Create or join ..." are not actually teams but have a display
     * string and a resourceId that makes them distinguishable.
     */
    class TeamDisplay {

        int stringResourceId;
        String display;
        Team team;

        TeamDisplay(@NonNull Team t) {
            team = t;
            stringResourceId = -1;
            display = team.getDomainName();
        }

        TeamDisplay(int resourceId) {
            team = null;
            stringResourceId = resourceId;
            display = EventListActivity.this.getString(stringResourceId);
        }

        @Override
        public String toString() {
            return display;
        }

        @Override
        public boolean equals(Object o) {
            boolean isEqual = false;
            if (o instanceof TeamDisplay) {
                TeamDisplay other = (TeamDisplay) o;
                if (team == null) {
                    isEqual = other.team == null && stringResourceId == other.stringResourceId;
                } else {
                    isEqual = team.equals(other.team);
                }
            }

            return isEqual;
        }
    }

    /**
     * Firebase Auth listener implementation.
     */
    private class AuthListener implements FirebaseAuth.AuthStateListener {

        @Override
        public void onAuthStateChanged(@NonNull FirebaseAuth firebaseAuth) {
            FirebaseUser currentUser = firebaseAuth.getCurrentUser();
            if (currentUser != null) {
                onLogin(currentUser);
            } else {
                onLogout();
            }
        }
    }

    private static final String LOG_TAG = "EventListActivity";
    /** Result tag constant for the Firebase AuthUI result. */
    private static final int RESULT_AUTH_ID = 20532;

    /**
     * Create a new dummy account for the sync adapter
     *
     * @param context The application context
     */
    public static Account CreateSyncAccount(Context context) {
        // Create the account type and default account
        Account newAccount = new Account(
                SYNC_ACCOUNT, SYNC_ACCOUNT_TYPE);
        // Get an instance of the Android account manager
        AccountManager accountManager =
                (AccountManager) context.getSystemService(
                        ACCOUNT_SERVICE);
        /*
         * Add the account and account type, no password or user data
         * If successful, return the Account object, otherwise report an error.
         */
        accountManager.addAccountExplicitly(newAccount, null, null);
        return newAccount;
    }

    private FirebaseAuth auth;
    private FirebaseAuth.AuthStateListener authListener;
    private FirebaseUser theUser;

    private Account syncAccount;

    private PomodoroFirebaseHelper database;

    @BindView(R.id.team_spinner)
    Spinner teamSpinner;
    @BindView(R.id.recycler_events)
    RecyclerView recycler;
    @BindView(R.id.ad_view)
    AdView adView;
    private EventListAdapter adapter;

    private ArrayAdapter<TeamDisplay> teamListAdapter;

    private String teamDomain = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_event_list);
        ButterKnife.bind(this);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        syncAccount = CreateSyncAccount(this);
        ContentResolver.addPeriodicSync(
                syncAccount,
                SYNC_AUTHORITY,
                Bundle.EMPTY,
                720L * 60L);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        auth = FirebaseAuth.getInstance();
        authListener = new AuthListener();

        RecyclerView.LayoutManager lm = new GridLayoutManager(this, 1);
        recycler.setLayoutManager(lm);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showNewEvent();
            }
        });

        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState);
        } else {
            teamDomain = null;
        }
    }

    @Override
    protected void onResume() {
        auth.addAuthStateListener(authListener);
        super.onResume();

        AdRequest request = new AdRequest.Builder().build();
        adView.loadAd(request);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (adapter != null) {
            adapter.cleanup();
            adapter = null;
        }
        if (authListener != null) {
            auth.removeAuthStateListener(authListener);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (this.teamDomain != null && !this.teamDomain.isEmpty()) {
            outState.putString(STORE_TEAM_DOMAIN, this.teamDomain);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        this.teamDomain = savedInstanceState.getString(STORE_TEAM_DOMAIN);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_event_list, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Bundle transitions = ActivityOptionsCompat.makeSceneTransitionAnimation(this)
                    .toBundle();
            startActivity(new Intent(this, SettingsActivity.class), transitions);
            return true;
        } else if (id == R.id.action_teams) {
            Bundle transitions = ActivityOptionsCompat.makeSceneTransitionAnimation(this)
                    .toBundle();
            startActivity(new Intent(this, TeamJoinActivity.class), transitions);
            return true;
        } else if (id == R.id.action_logout) {
            this.auth.signOut();
        }

        return super.onOptionsItemSelected(item);
    }

    private void onLogin(final FirebaseUser user) {
        this.theUser = user;
        database = new PomodoroFirebaseHelper();
        database.queryUser(theUser.getUid())
                .then(new Promise.PromiseReceiver() {
                    @Override
                    public Object receive(Object t) {
                        User u = (User) t;
                        if (u == null) {
                            u = new User();
                            u.setUid(theUser.getUid());
                            u.setDisplayName(theUser.getDisplayName());
                            u.setEmail(theUser.getEmail());
                            database.createUser(u);
                        }
                        Log.d(LOG_TAG, "login team domain " + teamDomain);
                        if (teamDomain == null || teamDomain.length() == 0) {
                            Log.d(LOG_TAG, "login user " + u.getUid());
                            Log.d(LOG_TAG, "login recent team " + u.getRecentTeam());
                            teamDomain = u.getRecentTeam();
                        }
                        populateTeams(teamDomain);
                        return u;
                    }
                });
        sendBroadcast(PomodoroWidget.broadcastUodate(this));
    }

    private void onLogout() {
        this.theUser = null;
        if (this.adapter != null) {
            this.adapter.cleanup();
        }
        startActivityForResult(
                AuthUI.getInstance()
                        .createSignInIntentBuilder()
                        .setAvailableProviders(
                                Arrays.asList(new AuthUI.IdpConfig.Builder(AuthUI.EMAIL_PROVIDER).build(),
                                        new AuthUI.IdpConfig.Builder(AuthUI.GOOGLE_PROVIDER).build()))
                        .setIsSmartLockEnabled(false)
                        .build(),
                RESULT_AUTH_ID);
    }

    private void eventSelected(final Event event) {
        Log.d(LOG_TAG, "EVENT SELECTED");
        if (event.isActive()) {
            Promise joined = (event.getTeamDomain() != null && event.getTeamDomain().length() > 0 && !event.hasMember(theUser.getUid())
                    ? database.joinEvent(event, theUser.getUid(), new Date())
                    : Promise.resolved(true));

            joined.then(new Promise.PromiseReceiver() {
                @Override
                public Object receive(Object t) {
                    return database.updateUserRecentEvent(theUser.getUid(), event);
                }
            }).then(new Promise.PromiseReceiver() {
                @Override
                public Object receive(Object t) {
                    sendBroadcast(PomodoroWidget.broadcastUodate(EventListActivity.this));
                    Intent timerActivityIntent = new Intent(EventListActivity.this, EventTimerActivity.class);
                    Bundle transitions = ActivityOptionsCompat.makeSceneTransitionAnimation(EventListActivity.this)
                            .toBundle();
                    timerActivityIntent.putExtra(EXTRA_EVENT_ID, event.getKey());
                    timerActivityIntent.putExtra(STORE_TEAM_DOMAIN, teamDomain);
                    startActivity(timerActivityIntent, transitions);
                    return t;
                }
            });
        } else {
            Intent summaryViewIntent = new Intent(this, EventSummaryActivity.class);
            Bundle transitions = ActivityOptionsCompat.makeSceneTransitionAnimation(this)
                    .toBundle();
            summaryViewIntent.putExtra(EXTRA_EVENT_ID, event.getKey());
            summaryViewIntent.putExtra(STORE_TEAM_DOMAIN, teamDomain);
            startActivity(summaryViewIntent, transitions);
        }
    }

    private void showNewEvent() {
        final Date now = new Date();
        final String defaultEventName = DateFormat.getDateInstance(DateFormat.SHORT).format(now);
        final String name = adapter.getUniqueEventName(defaultEventName);
        Bundle args = new Bundle();
        args.putString(CreateEventDlgFragment.BUNDLE_KEY_EVENTNAME, name);
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(EventListActivity.this);
        String activityMinutes = preferences.getString(getString(R.string.pref_activity_time_key), "");
        String breakMinutes = preferences.getString(getString(R.string.pref_break_time_key), "");

        try {
            args.putInt(CreateEventDlgFragment.BUNDLE_KEY_ACTIVITY_LENGTH, Integer.parseInt(activityMinutes));
            args.putInt(CreateEventDlgFragment.BUNDLE_KEY_BREAK_LENGTH, Integer.parseInt(breakMinutes));
        } catch (NumberFormatException nfe) {
            Log.d(LOG_TAG, "problem parsing preferences");
        }
        CreateEventDlgFragment fragment = new CreateEventDlgFragment();
        fragment.setArguments(args);
        fragment.setListener(new CreateEventDlgFragment.CreateEventListener() {
            @Override
            public void doCreateEvent(CreateEventDlgFragment.CreateEventParams params) {
                EventListActivity.this.createEvent(params);
            }
        });
        fragment.show(getFragmentManager(), "CreateEventDlgFragment");
    }

    void createEvent(CreateEventDlgFragment.CreateEventParams params) {
        Log.d(LOG_TAG, "Create event " + params.eventName);

        final Date createDate = new Date();
        final String user = theUser.getUid();
        Event newEvent = new Event(params.eventName, user, createDate, true,
                params.activityMinutes, params.breakMinutes, teamDomain);
        String key = database.createEvent(newEvent);
        Log.d(LOG_TAG, "   created event at " + key);
    }

    @OnItemSelected(R.id.team_spinner)
    void spinnerChanged() {
        TeamDisplay selected = (TeamDisplay) teamSpinner.getSelectedItem();
        if (selected.team != null) {
            viewTeamData(selected.team.getDomainName());
        } else if (selected.stringResourceId == R.string.option_private_events) {
            viewTeamData(null);
        } else if (selected.stringResourceId == R.string.option_my_team) {
            Bundle transitions = ActivityOptionsCompat.makeSceneTransitionAnimation(this)
                    .toBundle();
            startActivity(new Intent(this, TeamJoinActivity.class), transitions);
        }
    }

    private void setTeamSelection(String teamDomain) {
        int selectionIndex = -1;
        int dfltSelection = -1;
        boolean isTeam = teamDomain != null && teamDomain.length() > 0;

        for (int i = 0; i < teamListAdapter.getCount(); ++i) {
            TeamDisplay td = teamListAdapter.getItem(i);
            if (isTeam && td.team != null && teamDomain.equals(td.team.getDomainName())) {
                selectionIndex = i;
                break;
            } else if (!isTeam && td.stringResourceId == R.string.option_private_events) {
                selectionIndex = i;
                break;
            } else if (td.stringResourceId == R.string.option_private_events) {
                dfltSelection = i;
            }
        }

        if (selectionIndex != -1) {
            teamSpinner.setSelection(selectionIndex);
        } else if (dfltSelection != -1) {
            teamSpinner.setSelection(dfltSelection);
        }
    }

    private void viewTeamData(String teamDomain) {
        if (adapter != null) {
            adapter.cleanup();
        }

        this.teamDomain = teamDomain;
        database.updateUserRecentTeam(theUser.getUid(), teamDomain);

        DatabaseReference eventReference = database.getEventsReference(teamDomain, theUser.getUid());
        adapter = new EventListAdapter(eventReference, new EventListAdapter.EventSelectionListener() {
            @Override
            public void eventSelected(Event event) {
                EventListActivity.this.eventSelected(event);
            }
        });
        recycler.setAdapter(adapter);
    }

    private void populateTeams(final String selectedTeam) {
        teamDomain = selectedTeam;
        teamListAdapter = new ArrayAdapter<TeamDisplay>(this, R.layout.custom_spinner_item);
        teamListAdapter.add(new TeamDisplay(R.string.option_private_events));
        teamListAdapter.add(new TeamDisplay(R.string.option_my_team));
        teamSpinner.setAdapter(teamListAdapter);
        database.queryUserTeams(theUser.getUid(), VALID_MEMBERS).then(new Promise.PromiseReceiver() {
            @Override
            public Object receive(Object t) {
                List<Team> teams = (List<Team>)t;
                boolean selectedFound = false;
                for (Team tm : teams) {
                    teamListAdapter.add(new TeamDisplay(tm));
                    selectedFound = selectedFound || tm.getDomainName().equals(selectedTeam);
                }
                if (selectedFound) {
                    setTeamSelection(selectedTeam);
                }
                // now also subscribe to changes
                database.subscribeUserTeams(theUser.getUid(), new ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                        Log.d(LOG_TAG, "onChildAdded " + dataSnapshot.getKey());
                        String teamKey = dataSnapshot.getKey();
                        if (findTeamDisplay(teamKey) == null) {
                            String teamValue = dataSnapshot.getValue(String.class);
                            try {
                                TeamMember.Role role = TeamMember.Role.valueOf(teamValue);
                                if (VALID_MEMBERS.contains(role)) {
                                    database.queryTeam(teamKey).then(new Promise.PromiseReceiver() {
                                        @Override
                                        public Object receive(Object t) {
                                            Team team = (Team) t;
                                            teamListAdapter.add(new TeamDisplay(team));
                                            return t;
                                        }
                                    });
                                }
                            } catch (IllegalArgumentException e) {
                                Log.d(LOG_TAG, "Team role not recognized " + teamValue);
                            }
                        }
                    }

                    @Override
                    public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                        Log.d(LOG_TAG, "onChildChanged " + dataSnapshot.getKey());
                        // could be a role change, applied -> member which would add an option
                        String teamKey = dataSnapshot.getKey();
                        String teamValue = dataSnapshot.getValue(String.class);
                        try {
                            TeamMember.Role role = TeamMember.Role.valueOf(teamValue);
                            TeamDisplay existingMember = findTeamDisplay(teamKey);
                            if (VALID_MEMBERS.contains(role)) {
                                if (existingMember != null) {
                                    // update the team record to represent current role state
                                    existingMember.team.findTeamMember(theUser.getUid()).setRole(role);
                                } else {
                                    database.queryTeam(teamKey).then(new Promise.PromiseReceiver() {
                                        @Override
                                        public Object receive(Object t) {
                                            Team team = (Team) t;
                                            teamListAdapter.add(new TeamDisplay(team));
                                            return t;
                                        }
                                    });
                                }
                            } else {
                                // perhaps no longer a member?
                                if (existingMember != null) {
                                    teamListAdapter.remove(existingMember);
                                }
                            }

                        } catch (IllegalArgumentException e) {
                            Log.d(LOG_TAG, "Team role not recognized " + teamValue);
                        }
                    }

                    @Override
                    public void onChildRemoved(DataSnapshot dataSnapshot) {
                        Log.d(LOG_TAG, "onChildRemoved " + dataSnapshot.getKey());
                        String teamKey = dataSnapshot.getKey();
                        for (int i = 0; i < teamListAdapter.getCount(); ++i) {
                            TeamDisplay td = teamListAdapter.getItem(i);
                            if (td.team.getDomainName().equals(teamKey)) {
                                teamListAdapter.remove(td);
                                break;
                            }
                        }
                    }

                    @Override
                    public void onChildMoved(DataSnapshot dataSnapshot, String s) {
                        // no-op
                        Log.d(LOG_TAG, "onChildMoved " + dataSnapshot.getKey());
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        // no-op
                        Log.d(LOG_TAG, "onCancelled " + databaseError.toString());
                    }
                });
                return t;
            }
        });

        // if the selection is private events, we can do that now
        if (selectedTeam == null || selectedTeam.isEmpty()) {
            setTeamSelection(selectedTeam);
            viewTeamData(selectedTeam);
        }
    }

    private TeamDisplay findTeamDisplay(final String teamKey) {
        for (int i = 0; i < teamListAdapter.getCount(); ++i) {
            TeamDisplay td = teamListAdapter.getItem(i);
            if (td.team != null && td.team.getDomainName().equals(teamKey)) {
                return td;
            }
        }
        return null;
    }
}
