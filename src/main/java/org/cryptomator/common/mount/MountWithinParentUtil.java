package org.cryptomator.common.mount;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class MountWithinParentUtil {

	private static final Logger LOG = LoggerFactory.getLogger(Mounter.class);
	private static final String HIDEAWAY_PREFIX = ".~$";
	private static final String HIDEAWAY_SUFFIX = ".tmp";
	private static final String WIN_HIDDEN_ATTR = "dos:hidden";

	private MountWithinParentUtil() {}

	static void prepareParentNoMountPoint(Path mountPoint) throws IllegalMountPointException {
		Path hideaway = getHideaway(mountPoint);
		var mpExists = Files.exists(mountPoint, LinkOption.NOFOLLOW_LINKS);
		var hideExists = Files.exists(hideaway, LinkOption.NOFOLLOW_LINKS);

		//TODO: possible improvement by just deleting an _empty_ hideaway
		if (mpExists && hideExists) { //both resources exist (whatever type)
			throw new ExistingHideawayException(mountPoint, hideaway, "Hideaway (" + hideaway + ") already exists for mountpoint: " + mountPoint);
		} else if (!mpExists && !hideExists) { //neither mountpoint nor hideaway exist
			throw new MountPointNotExistingException(mountPoint);
		} else if (!mpExists) { //only hideaway exists
			checkIsDirectory(hideaway);
			LOG.info("Mountpoint {} seems to be not properly cleaned up. Will be fixed on unmount.", mountPoint);
			try {
				if (SystemUtils.IS_OS_WINDOWS) {
					Files.setAttribute(hideaway, WIN_HIDDEN_ATTR, true, LinkOption.NOFOLLOW_LINKS);
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		} else { //only mountpoint exists
			try {
				checkIsDirectory(mountPoint);
				checkIsEmpty(mountPoint);

				Files.move(mountPoint, hideaway);
				if (SystemUtils.IS_OS_WINDOWS) {
					Files.setAttribute(hideaway, WIN_HIDDEN_ATTR, true, LinkOption.NOFOLLOW_LINKS);
				}
				int attempts = 0;
				while (!Files.notExists(mountPoint)) {
					if (attempts >= 10) {
						throw new MountPointCleanupFailedException(mountPoint);
					}
					Thread.sleep(1000);
					attempts++;
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}
	}

	static void cleanup(Path mountPoint) {
		Path hideaway = getHideaway(mountPoint);
		try {
			waitForMountpointRestoration(mountPoint);
			Files.move(hideaway, mountPoint);
			if (SystemUtils.IS_OS_WINDOWS) {
				Files.setAttribute(mountPoint, WIN_HIDDEN_ATTR, false);
			}
		} catch (IOException e) {
			LOG.error("Unable to restore hidden directory to mountpoint {}.", mountPoint, e);
		}
	}

	//on Windows removing the mountpoint takes some time, so we poll for at most 3 seconds
	private static void waitForMountpointRestoration(Path mountPoint) throws FileAlreadyExistsException {
		int attempts = 0;
		while (!Files.notExists(mountPoint, LinkOption.NOFOLLOW_LINKS)) {
			attempts++;
			if (attempts >= 5) {
				throw new FileAlreadyExistsException("Timeout waiting for mountpoint cleanup for " + mountPoint + " .");
			}

			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new FileAlreadyExistsException("Interrupted before mountpoint " + mountPoint + " was cleared");
			}
		}
	}

	private static void checkIsDirectory(Path toCheck) throws IllegalMountPointException {
		if (!Files.isDirectory(toCheck, LinkOption.NOFOLLOW_LINKS)) {
			throw new MountPointNotEmptyDirectoryException(toCheck, "Mountpoint is not a directory: " + toCheck);
		}
	}

	private static void checkIsEmpty(Path toCheck) throws IllegalMountPointException, IOException {
		try (var dirStream = Files.list(toCheck)) {
			if (dirStream.findFirst().isPresent()) {
				throw new MountPointNotEmptyDirectoryException(toCheck, "Mountpoint directory is not empty: " + toCheck);
			}
		}
	}

	//visible for testing
	static Path getHideaway(Path mountPoint) {
		return mountPoint.resolveSibling(HIDEAWAY_PREFIX + mountPoint.getFileName().toString() + HIDEAWAY_SUFFIX);
	}

}
