package edu.droidshark.android.ui.activities;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.v4.app.FragmentTransaction;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.ActionBar.Tab;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.dropbox.client2.DropboxAPI;
import com.dropbox.client2.android.AndroidAuthSession;
import com.dropbox.client2.session.AccessTokenPair;
import com.dropbox.client2.session.AppKeyPair;
import com.dropbox.client2.session.TokenPair;
import com.voytechs.jnetstream.codec.Packet;

import edu.droidshark.R;
import edu.droidshark.android.dropbox.UploadCaptureFile;
import edu.droidshark.android.services.TCPDumpBinder;
import edu.droidshark.android.services.TCPDumpService;
import edu.droidshark.android.ui.fragments.activity.AboutFragment;
import edu.droidshark.android.ui.fragments.activity.HelpFragment;
import edu.droidshark.android.ui.fragments.activity.PacketViewFragment;
import edu.droidshark.android.ui.fragments.activity.SaveFragment;
import edu.droidshark.android.ui.fragments.activity.SnifferFragment;
import edu.droidshark.android.ui.fragments.activity.UploadFragment;
import edu.droidshark.constants.SnifferConstants;
import edu.droidshark.tcpdump.FilterDatabase;
import edu.droidshark.tcpdump.TCPDumpListener;
import edu.droidshark.tcpdump.TCPDumpOptions;
import edu.droidshark.tcpdump.TCPDumpUtils;

public class DroidSharkActivity extends SherlockFragmentActivity implements
		ActionBar.TabListener
{
	private static final String TAG = "DroidSharkActivity";
	private int currPane = SnifferConstants.SNIFFERPANE, packetsReceived,
			totalPackets;
	private SnifferFragment snifferFragment;
	private PacketViewFragment packetViewFragment;
	private ActionBar.Tab mSnifTab, mPVTab;
	private ActionBar mActionBar;
	private TextView statusTextView, filePacketsTextView, appPacketsTextView;
	public boolean tcpdumpIsRunning, isBound, dropboxLoggedIn;
	private TCPDumpService tService;
	private Process tProcess;
	public FilterDatabase filterDB;
	private DropboxAPI<AndroidAuthSession> dropboxAPI;

	private ServiceConnection sConn = new ServiceConnection()
	{
		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * android.content.ServiceConnection#onServiceConnected(android.content
		 * .ComponentName, android.os.IBinder)
		 */
		@Override
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			tService = ((TCPDumpBinder) service).getService();
			tService.settListener(new TCPDumpCallbacks());
			totalPackets = tService.getCount();
			DroidSharkActivity.this.updatePacketCount();
			isBound = true;
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * android.content.ServiceConnection#onServiceDisconnected(android.content
		 * .ComponentName)
		 */
		@Override
		public void onServiceDisconnected(ComponentName name)
		{
			isBound = false;
		}
	};

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		// Check to see if tcpdump is present
		try
		{
			FileInputStream fis = openFileInput("tcpdump");
			fis.close();
		} catch (FileNotFoundException e)
		{
			// If file not found need to create
			TCPDumpUtils.createTCPDump(this);
		} catch (IOException e)
		{
			Log.e(getClass().getSimpleName(),
					"IOException, message=" + e.getMessage());
		}

		// Get database of filters
		filterDB = new FilterDatabase(this);

		setContentView(R.layout.main);

		// We create a new AuthSession so that we can use the Dropbox API.
		AndroidAuthSession session = buildSession();
		dropboxAPI = new DropboxAPI<AndroidAuthSession>(session);
		dropboxLoggedIn = dropboxAPI.getSession().isLinked();

		snifferFragment = (SnifferFragment) getSupportFragmentManager()
				.findFragmentById(R.id.snifferFragment);
		packetViewFragment = (PacketViewFragment) getSupportFragmentManager()
				.findFragmentById(R.id.packetViewFragment);

		// Status related text views
		statusTextView = (TextView) findViewById(R.id.statusTextView);
		filePacketsTextView = (TextView) findViewById(R.id.filePacketsTextView);
		appPacketsTextView = (TextView) findViewById(R.id.appPacketsTextView);

		if (savedInstanceState != null)
		{
			currPane = savedInstanceState.getInt("currPane");
			packetsReceived = savedInstanceState.getInt("packetsReceived");
			totalPackets = savedInstanceState.getInt("totalPackets");
		}

		// Start service onCreate(), so it is not destroyed when activity
		// unbinds.
		if (SnifferConstants.DEBUG)
			Log.d(TAG, "Starting service");
		startService(new Intent(this, TCPDumpService.class));

		mActionBar = getSupportActionBar();
		mActionBar.setDisplayHomeAsUpEnabled(false);

		// Set display to last pane shown
		if (!getResources().getBoolean(R.bool.has_two_panes))
		{
			// Create tabs
			mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
			mSnifTab = mActionBar.newTab().setText(R.string.sniffer)
					.setTabListener(this);
			mPVTab = mActionBar.newTab().setText(R.string.packet_view)
					.setTabListener(this);
			if (currPane == SnifferConstants.SNIFFERPANE)
			{
				if (SnifferConstants.DEBUG)
					Log.d(TAG, "currPane=SNIFFERPANE");
				mActionBar.addTab(mSnifTab, true);
				mActionBar.addTab(mPVTab, false);
			} else if (currPane == SnifferConstants.PACKETVIEWPANE)
			{
				if (SnifferConstants.DEBUG)
					Log.d(TAG, "currPane=PACKETVIEWPANE");
				mActionBar.addTab(mSnifTab, false);
				mActionBar.addTab(mPVTab, true);
			}
		} else
			mActionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
	}

	@Override
	protected void onStart()
	{
		super.onStart();

		// Bind to the service
		if (SnifferConstants.DEBUG)
			Log.d(TAG, "Binding TCPDumpService");
		bindService(new Intent(this, TCPDumpService.class), sConn, 0);
	}

	@Override
	public void onResume()
	{
		super.onResume();
		tcpdumpIsRunning = TCPDumpUtils.isTCPDumpRunning();
		setStatusText(tcpdumpIsRunning);
		updatePacketCount();
		if (SnifferConstants.DEBUG)
			Log.d(TAG, "tcpdumpRunning=" + tcpdumpIsRunning);

		AndroidAuthSession session = dropboxAPI.getSession();

		// Complete dropbox authentication
		if (session.authenticationSuccessful())
		{
			try
			{
				// Mandatory call to complete the auth
				session.finishAuthentication();

				// Store it locally in our app for later use
				TokenPair tokens = session.getAccessTokenPair();
				storeKeys(tokens.key, tokens.secret);
				dropboxLoggedIn = true;
				Toast.makeText(this, "Dropbox login successful",
						Toast.LENGTH_SHORT).show();
			} catch (IllegalStateException e)
			{
				Toast.makeText(
						this,
						"Couldn't authenticate with Dropbox:"
								+ e.getLocalizedMessage(), Toast.LENGTH_SHORT)
						.show();
				Log.i(TAG, "Error authenticating", e);
			}
		}
	}

	@Override
	protected void onStop()
	{
		super.onStop();

		// Unbind the service
		if (isBound)
		{
			tService.settListener(null);
			if (SnifferConstants.DEBUG)
				Log.d(TAG, "Unbinding TCPDumpService");
			unbindService(sConn);
		}
	}

	@Override
	public void onDestroy()
	{
		super.onDestroy();

		// Keep service running if tcpdump is running in background
		if (!tcpdumpIsRunning)
			stopService(new Intent(this, TCPDumpService.class));

		filterDB.close();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		MenuInflater inflater = this.getSupportMenuInflater();
		inflater.inflate(R.menu.main_menu, menu);

		// Calling super after populating the menu is necessary here to ensure
		// that the action bar helpers have a chance to handle this event.
		return super.onCreateOptionsMenu(menu);
	}

	
	@Override
	public boolean onPrepareOptionsMenu(Menu menu)
	{
		MenuItem item = menu.findItem(R.id.logout);

        item.setVisible(dropboxLoggedIn);
        
        return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);

		outState.putInt("currPane", currPane);
		outState.putInt("packetsReceived", packetsReceived);
		outState.putInt("totalPackets", totalPackets);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		// Handle item selection
		switch (item.getItemId())
		{
		case R.id.exit:
			if (tcpdumpIsRunning)
				stopSniffer();
			finish();
			return true;
		case R.id.save:
			if (tcpdumpIsRunning)
				Toast.makeText(this, "Sniffer must be stopped",
						Toast.LENGTH_SHORT).show();
			else
				openSaveDialog();
			return true;
		case R.id.start:
			if (tcpdumpIsRunning)
				Toast.makeText(this, "Sniffer already running",
						Toast.LENGTH_SHORT).show();
			else
				startSniffer();
			return true;
		case R.id.stop:
			if (tcpdumpIsRunning)
				stopSniffer();
			else
				Toast.makeText(this, "Sniffer not running", Toast.LENGTH_SHORT)
						.show();
			return true;
		case R.id.upload:
			if (dropboxLoggedIn)
				openUploadDialog();
			else
				dropboxAPI.getSession().startAuthentication(this);
			return true;
		case R.id.logout:
			dropboxLogOut();
			return true;
		case R.id.help:
			openHelpDialog();
			return true;
		case R.id.about:
			openAboutDialog();
			return true;
		default:
			return false;
		}
	}

	/**
	 * Opens the help dialog
	 */
	private void openHelpDialog()
	{
		new HelpFragment().show(getSupportFragmentManager(), "help");
	}

	
	/**
	 * Opens the about dialog
	 */
	private void openAboutDialog()
	{
		new AboutFragment().show(getSupportFragmentManager(), "about");
	}
	
	/**
	 * Creates a dropbox session
	 * 
	 * @return The authorized session
	 */
	private AndroidAuthSession buildSession()
	{
		AppKeyPair appKeyPair = new AppKeyPair(
				SnifferConstants.DROPBOX_APP_KEY,
				SnifferConstants.DROPBOX_APP_SECRET);
		AndroidAuthSession session;

		String[] stored = getKeys();
		if (stored != null)
		{
			AccessTokenPair accessToken = new AccessTokenPair(stored[0],
					stored[1]);
			session = new AndroidAuthSession(appKeyPair,
					SnifferConstants.ACCESS_TYPE, accessToken);
		} else
		{
			session = new AndroidAuthSession(appKeyPair,
					SnifferConstants.ACCESS_TYPE);
		}

		return session;
	}

	/**
	 * Get the access keys returned from DropBox Trusted Authenticator
	 * 
	 * @return Array of [access_key, access_secret], or null if none stored
	 */
	private String[] getKeys()
	{
		SharedPreferences prefs = getSharedPreferences("dropbox", 0);
		String key = prefs.getString(SnifferConstants.DROPBOX_KEY_NAME, null);
		String secret = prefs.getString(SnifferConstants.DROPBOX_SECRET_NAME,
				null);
		if (key != null && secret != null)
		{
			String[] ret = new String[2];
			ret[0] = key;
			ret[1] = secret;
			return ret;
		} else
		{
			return null;
		}
	}

	/**
	 * Stores the access keys returned from DropBox Trusted Authenticator
	 */
	private void storeKeys(String key, String secret)
	{
		// Save the access key for later
		SharedPreferences prefs = getSharedPreferences("dropbox", 0);
		Editor edit = prefs.edit();
		edit.putString(SnifferConstants.DROPBOX_KEY_NAME, key);
		edit.putString(SnifferConstants.DROPBOX_SECRET_NAME, secret);
		edit.commit();
	}

	/**
	 * Logs out from dropbox
	 */
	private void dropboxLogOut()
	{
		// Remove credentials from the session
		dropboxAPI.getSession().unlink();

		// Clear our stored keys
		SharedPreferences prefs = getSharedPreferences("dropbox", 0);
		Editor edit = prefs.edit();
		edit.clear();
		edit.commit();
		dropboxLoggedIn = false;
		Toast.makeText(this, "Log Out Successful", Toast.LENGTH_SHORT).show();
	}

	/**
	 * Opens save prompt
	 */
	private void openSaveDialog()
	{
		if (new File(getExternalFilesDir(null) + "/capture.pcap").exists())
			new SaveFragment(Environment.getExternalStorageDirectory()
					.getPath() + "/Capture").show(getSupportFragmentManager(),
					"save");
		else
			Toast.makeText(this, "No capture file found", Toast.LENGTH_SHORT)
					.show();
	}

	/**
	 * Saves the last capture file with specified name
	 * 
	 * @param filename
	 *            The filename to be saved
	 */
	public void saveCaptureFile(String filename)
	{
		File path = Environment.getExternalStorageDirectory();
		File capDir = new File(path.getPath() + "/Capture");
		File saveFile = new File(capDir.getPath() + "/" + filename);

		try
		{
			// Make sure the Pictures directory exists.
			capDir.mkdirs();
			File captureFile = new File(getExternalFilesDir(null)
					+ "/capture.pcap");
			FileUtils.copyFile(captureFile, saveFile);
		} catch (IOException e)
		{
			Log.e(TAG, "Error writing file, msg=" + e.getMessage());
		}

	}

	/**
	 * Opens upload prompt
	 */
	private void openUploadDialog()
	{
		File path = Environment.getExternalStorageDirectory();
		File capDir = new File(path.getPath() + "/Capture");
		if (capDir.listFiles() != null)
			new UploadFragment(capDir).show(getSupportFragmentManager(),
					"upload");
		else
			Toast.makeText(this, "No files available to upload",
					Toast.LENGTH_SHORT).show();
	}

	/**
	 * Uploads the given file via dropbox
	 * 
	 * @param file
	 *            The file to be uploaded
	 */
	public void uploadCaptureFile(File file)
	{
		new UploadCaptureFile(this, dropboxAPI, file).execute();
	}

	/**
	 * Starts the sniffer
	 */
	private void startSniffer()
	{
		try
		{
			tcpdumpIsRunning = true;
			EditText packetLenLimEditText = snifferFragment
					.getPacketLenLimEditText();
			TCPDumpOptions tcpdumpOptions = snifferFragment.getTCPDumpOptions();
			closeIME(packetLenLimEditText.getWindowToken());
			tcpdumpOptions.setPacketLenLim(Integer.valueOf(packetLenLimEditText
					.getText().toString()));
			Process proc = TCPDumpUtils.startTCPDump(this, tcpdumpOptions);
			if (proc == null)
			{
				Toast.makeText(this,
						"Syntax error occurred, check your filter",
						Toast.LENGTH_SHORT).show();
				tcpdumpIsRunning = false;
			} else
			{
				tProcess = proc;
				tService.openFileStream(tProcess);
				packetsReceived = 0;
				totalPackets = 0;
				updatePacketCount();
			}
		} catch (NumberFormatException e)
		{
			Toast.makeText(this, "Invalid Packet Length Limit",
					Toast.LENGTH_SHORT).show();
			tcpdumpIsRunning = false;
		}

		setStatusText(tcpdumpIsRunning);

		packetViewFragment.clearPackets();
	}

	/**
	 * Stops the sniffer
	 */
	private void stopSniffer()
	{
		closeIME(snifferFragment.getPacketLenLimEditText().getWindowToken());
		TCPDumpUtils.stopTCPDump();
		tService.closeFileStream();
		tcpdumpIsRunning = false;
		setStatusText(tcpdumpIsRunning);
	}

	/**
	 * Check sniffer status and update display string
	 * 
	 * @param isRunning
	 *            Whether tcpdump is running
	 */
	private void setStatusText(boolean isRunning)
	{
		if (isRunning)
		{
			statusTextView.setText("RUNNING");
			statusTextView.setTextColor(Color.GREEN);
		} else
		{
			statusTextView.setText("STOPPED");
			statusTextView.setTextColor(Color.RED);
		}
	}

	/**
	 * Updates the packet text views
	 * 
	 */
	private void updatePacketCount()
	{
		filePacketsTextView.setText(totalPackets + "");
		appPacketsTextView.setText(packetsReceived + "");

	}

	/**
	 * Closes the soft keyboard
	 * 
	 * @param windowToken
	 *            The field's(ie EditText) window token (use getWindowToken())
	 *            that currently is using the soft keyboard.
	 */
	public void closeIME(IBinder windowToken)
	{
		InputMethodManager mgr = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		mgr.hideSoftInputFromWindow(windowToken, 0);
	}

	/**
	 * Shows the SnifferFragment
	 */
	public void showSniffer()
	{
		snifferFragment.getView().setVisibility(View.VISIBLE);
		packetViewFragment.getView().setVisibility(View.GONE);
		currPane = SnifferConstants.SNIFFERPANE;

		if (mSnifTab != null)
			mActionBar.setSelectedNavigationItem(mSnifTab.getPosition());
	}

	/**
	 * Shows the PacketViewFragment
	 */
	public void showPacketView()
	{
		snifferFragment.getView().setVisibility(View.GONE);
		packetViewFragment.getView().setVisibility(View.VISIBLE);
		currPane = SnifferConstants.PACKETVIEWPANE;

		if (mPVTab != null)
			mActionBar.setSelectedNavigationItem(mPVTab.getPosition());
	}

	/**
	 * Adds a filter to the database
	 * 
	 * @param name
	 *            Name of the filter
	 * @param filter
	 *            Filter string
	 */
	public void addFilter(String name, String filter)
	{
		ContentValues cv = new ContentValues();
		cv.put("name", name);
		cv.put("filter", filter);
		filterDB.getWritableDatabase().insert("filters", "name", cv);
		snifferFragment.addFilter(name, filter);
	}

	/**
	 * Edits a filter to the database
	 * 
	 * @param name
	 *            Name of the filter
	 * @param filter
	 *            Filter string
	 */
	public void editFilter(int id, String name, String filter)
	{
		ContentValues cv = new ContentValues();
		cv.put("name", name);
		cv.put("filter", filter);
		filterDB.getWritableDatabase().update("filters", cv, "_id=?",
				new String[] { String.valueOf(id) });
		snifferFragment.updateFilter(id, name, filter);
	}

	/**
	 * A class for doing something with callbacks from TCPDumpService
	 * 
	 * @author Sam SmithReams
	 * 
	 */
	public class TCPDumpCallbacks implements TCPDumpListener
	{

		/*
		 * (non-Javadoc)
		 * 
		 * @see edu.droidshark.tcpdump.TCPDumpListener#packetReceived(int)
		 */
		@Override
		public void packetReceived(final int numPackets, final Packet packet)
		{
			DroidSharkActivity.this.runOnUiThread(new Runnable()
			{

				@Override
				public void run()
				{
					totalPackets++;
					packetsReceived++;
					updatePacketCount();
					if (packetViewFragment != null)
						packetViewFragment.updatePacketCount(packet);
				}

			});
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.actionbarsherlock.app.ActionBar.TabListener#onTabSelected(com.
	 * actionbarsherlock.app.ActionBar.Tab,
	 * android.support.v4.app.FragmentTransaction)
	 */
	@Override
	public void onTabSelected(Tab tab, FragmentTransaction ft)
	{
		// Check if fragment is already initialized
		if (tab.getText().equals(getString(R.string.sniffer)))
			showSniffer();
		else if (tab.getText().equals(getString(R.string.packet_view)))
			showPacketView();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.actionbarsherlock.app.ActionBar.TabListener#onTabUnselected(com.
	 * actionbarsherlock.app.ActionBar.Tab,
	 * android.support.v4.app.FragmentTransaction)
	 */
	@Override
	public void onTabUnselected(Tab tab, FragmentTransaction ft)
	{/* Do nothing */
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.actionbarsherlock.app.ActionBar.TabListener#onTabReselected(com.
	 * actionbarsherlock.app.ActionBar.Tab,
	 * android.support.v4.app.FragmentTransaction)
	 */
	@Override
	public void onTabReselected(Tab tab, FragmentTransaction ft)
	{/* Do nothing */
	}
}