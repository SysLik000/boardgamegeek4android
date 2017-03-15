package com.boardgamegeek.tasks;

import android.accounts.Account;
import android.content.Context;
import android.text.TextUtils;

import com.boardgamegeek.auth.AccountUtils;
import com.boardgamegeek.auth.Authenticator;
import com.boardgamegeek.io.Adapter;
import com.boardgamegeek.io.BggService;
import com.boardgamegeek.model.User;
import com.boardgamegeek.model.persister.BuddyPersister;
import com.boardgamegeek.provider.BggContract;
import com.boardgamegeek.util.PresentationUtils;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;
import timber.log.Timber;

public class SyncUserTask extends SyncTask {
	private final String username;
	private final Call<User> call;

	public SyncUserTask(Context context, String username) {
		super(context);
		this.username = username;
		BggService bggService = Adapter.createForXml();
		call = bggService.user(username);
	}

	@Override
	protected String doInBackground() {
		if (TextUtils.isEmpty(username)) return "Tried to sync an unknown user.";

		try {
			Response<User> response = call.execute();
			if (response.isSuccessful()) {
				BuddyPersister persister = new BuddyPersister(context);
				User user = response.body();
				if (user == null || user.getId() == 0 || user.getId() == BggContract.INVALID_ID) {
					return String.format("Invalid user '%s'", username);
				}
				persister.save(user);

				Account account = Authenticator.getAccount(context);
				if (account != null && username.equals(account.name)) {
					AccountUtils.setUsername(context, user.name);
					AccountUtils.setFullName(context, PresentationUtils.buildFullName(user.firstName, user.lastName));
					AccountUtils.setAvatarUrl(context, user.avatarUrl);
				}

				Timber.i("Synced user '%s'", username);
			} else {
				return String.format("Unsuccessful user fetch with HTTP response code: %s", response.code());
			}
		} catch (IOException e) {
			Timber.w(e, "Unsuccessful user fetch");
			return (e.getLocalizedMessage());
		}
		return "";
	}

	@Override
	protected void onCancelled() {
		if (call != null) call.cancel();
	}

	@Override
	protected void onPostExecute(String errorMessage) {
		Timber.w(errorMessage);
		EventBus.getDefault().post(new Event(errorMessage, username));
	}

	public class Event {
		private final String errorMessage;
		private final String username;

		public Event(String errorMessage, String username) {
			this.errorMessage = errorMessage;
			this.username = username;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public String getUsername() {
			return username;
		}
	}
}
